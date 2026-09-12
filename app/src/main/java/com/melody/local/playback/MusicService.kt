package com.melody.local.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.RoomSongMetadataStore
import com.melody.local.data.SongMetadataOverride
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.melody.local.systemlyrics.SystemLyricSnapshot
import com.melody.local.systemlyrics.SystemLyricsCoordinator
import com.melody.local.systemlyrics.SystemLyricsSessionContract
import com.melody.local.systemlyrics.SystemLyricsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

@OptIn(markerClass = [UnstableApi::class])
class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var systemLyricsCoordinator: SystemLyricsCoordinator? = null
    private var systemLyricSnapshot = SystemLyricSnapshot()
    private lateinit var lyricsNotificationProvider: LyricsMediaNotificationProvider
    private lateinit var systemLyricsSettings: SystemLyricsSettings
    private var systemUiMetadataBackup: SystemUiMetadataBackup? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackModeRequestId = AtomicLong()
    private var songMetadata = emptyMap<Long, SongMetadataOverride>()
    private var lastMetadataAppliedQueueGeneration: Long? = null
    private var indexedQueueGeneration: Long? = null
    private var indexedQueueSize = -1
    private var queueIndicesBySongId = emptyMap<Long, List<Int>>()
    private var queueContentRevision = 0L
    private var queueContentRevisionGeneration: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var resumeAfterCompetingPlayback = false
    private var interruptedMediaId: String? = null
    private val resumePlaybackRunnable = Runnable { resumeAfterCompetingPlaybackEnds() }
    private val playbackActivityCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            updateResumeSchedule(configs)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val player = mediaSession?.player ?: return
            if (
                !audioMixingEnabled &&
                !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            ) {
                rememberInterruptedPlayback(player)
            } else {
                cancelInterruptedPlaybackResume()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                val queueGeneration = player.queueGenerationOrNull()
                if (queueGeneration == null || queueGeneration != lastMetadataAppliedQueueGeneration) {
                    applySavedSongMetadata(player)
                }
            }
            if (
                resumeAfterCompetingPlayback &&
                (
                    player.currentMediaItem?.mediaId != interruptedMediaId ||
                        player.playbackState == Player.STATE_IDLE ||
                        player.playbackState == Player.STATE_ENDED
                    )
            ) {
                cancelInterruptedPlaybackResume()
            }
        }
    }
    private val preferences by lazy {
        getSharedPreferences(PLAYBACK_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val playbackAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()
    private var playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL
    private var audioMixingEnabled = false

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            if (!result.isAccepted) return result
            return MediaSession.ConnectionResult.accept(
                result.availableSessionCommands.buildUpon()
                    .add(SET_PLAYBACK_MODE_COMMAND)
                    .add(SET_AUDIO_MIXING_COMMAND)
                    .build(),
                result.availablePlayerCommands,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_SET_PLAYBACK_MODE -> {
                    val requestedMode = args.playbackModeOrNull()
                        ?: return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_BAD_VALUE)
                        )
                    applyPlaybackMode(session.player as ExoPlayer, requestedMode)
                }

                ACTION_SET_AUDIO_MIXING -> {
                    val enabled = args.audioMixingEnabledOrNull()
                        ?: return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_BAD_VALUE)
                        )
                    applyAudioMixing(session.player as ExoPlayer, enabled)
                }

                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        playbackMode = preferences.getString(ARG_PLAYBACK_MODE, null)
            ?.let { stored -> runCatching { PlaybackMode.valueOf(stored) }.getOrNull() }
            ?: PlaybackMode.SEQUENTIAL
        audioMixingEnabled = preferences.getBoolean(ARG_AUDIO_MIXING_ENABLED, false)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(playbackAudioAttributes, !audioMixingEnabled)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)
        systemLyricsSettings = SystemLyricsSettings(this)
        lyricsNotificationProvider = LyricsMediaNotificationProvider(this, systemLyricsSettings)
        setMediaNotificationProvider(lyricsNotificationProvider)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
            .also { updateSessionExtras(it) }
        systemLyricsCoordinator = SystemLyricsCoordinator(this, player) { snapshot ->
            systemLyricSnapshot = snapshot
            lyricsNotificationProvider.updateSnapshot(snapshot)
            mediaSession?.let { session ->
                updateSystemUiLyricMetadata(session.player, snapshot)
                updateSessionExtras(session)
                if (session.player.currentMediaItem != null) {
                    onUpdateNotification(session, isPlaybackOngoing())
                }
            }
        }.also { it.start() }
        audioManager.registerAudioPlaybackCallback(playbackActivityCallback, mainHandler)
        // Observe committed state in the service, so finishing an edit does not require a live UI
        // controller. Room runs its queries off the main thread and invalidates after each commit.
        serviceScope.launch {
            try {
                RoomSongMetadataStore(
                    PlaylistDatabase.getInstance(this@MusicService).songStateDao()
                ).observeAll()
                    .collect { overrides ->
                        val previousMetadata = songMetadata
                        val changedSongIds = withContext(Dispatchers.Default) {
                            (previousMetadata.keys + overrides.keys)
                                .filterTo(mutableSetOf()) { songId ->
                                    previousMetadata[songId] != overrides[songId]
                                }
                        }
                        songMetadata = overrides
                        applySavedSongMetadata(player, changedSongIds)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MusicService", "Unable to observe saved song metadata", error)
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(
            session,
            startInForegroundRequired || resumeAfterCompetingPlayback,
        )
    }

    override fun onDestroy() {
        playbackModeRequestId.incrementAndGet()
        systemLyricsCoordinator?.close()
        systemLyricsCoordinator = null
        serviceScope.cancel()
        cancelInterruptedPlaybackResume()
        audioManager.unregisterAudioPlaybackCallback(playbackActivityCallback)
        mediaSession?.run {
            restoreSystemUiLyricMetadata(player)
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun rememberInterruptedPlayback(player: Player) {
        if (audioMixingEnabled) {
            cancelInterruptedPlaybackResume()
            return
        }
        val mediaId = player.currentMediaItem?.mediaId
        if (
            mediaId == null ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            cancelInterruptedPlaybackResume()
            return
        }
        interruptedMediaId = mediaId
        resumeAfterCompetingPlayback = true
        // A permanent focus loss has no matching gain callback, so wait for active playback to end.
        updateResumeSchedule(audioManager.activePlaybackConfigurations)
    }

    private fun updateResumeSchedule(configs: List<AudioPlaybackConfiguration>) {
        mainHandler.removeCallbacks(resumePlaybackRunnable)
        if (resumeAfterCompetingPlayback && configs.isEmpty()) {
            mainHandler.postDelayed(resumePlaybackRunnable, RESUME_DELAY_MS)
        }
    }

    private fun resumeAfterCompetingPlaybackEnds() {
        val player = mediaSession?.player
        if (
            audioMixingEnabled ||
            !resumeAfterCompetingPlayback ||
            player == null ||
            player.currentMediaItem?.mediaId != interruptedMediaId ||
            player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            cancelInterruptedPlaybackResume()
            return
        }
        if (audioManager.activePlaybackConfigurations.isNotEmpty()) return
        player.play()
    }

    private fun cancelInterruptedPlaybackResume() {
        mainHandler.removeCallbacks(resumePlaybackRunnable)
        resumeAfterCompetingPlayback = false
        interruptedMediaId = null
    }

    private fun applySavedSongMetadata(
        player: Player,
        changedSongIds: Set<Long>? = null,
    ) {
        lastMetadataAppliedQueueGeneration = player.queueGenerationOrNull()
        ensureQueueIndex(player)
        var changed = false
        val nextQueueContentRevision = queueContentRevision + 1L
        val targetSongIds = changedSongIds ?: queueIndicesBySongId.keys
        for (songId in targetSongIds) {
            val saved = songMetadata[songId] ?: continue
            for (index in queueIndicesBySongId[songId].orEmpty()) {
                val item = player.getMediaItemAt(index)
                val metadata = item.mediaMetadata
                val savedArtworkUri = saved.artworkPath?.toUri()
                if (metadata.title?.toString() == saved.title &&
                    metadata.artist?.toString() == saved.artist &&
                    metadata.albumTitle?.toString() == saved.album &&
                    (savedArtworkUri == null || metadata.artworkUri == savedArtworkUri)
                ) continue
                val updatedBuilder = metadata.buildUpon()
                    .setTitle(saved.title)
                    .setArtist(saved.artist)
                    .setAlbumTitle(saved.album)
                    .setExtras(
                        Bundle(metadata.extras ?: Bundle.EMPTY).apply {
                            putLong(EXTRA_QUEUE_CONTENT_REVISION, nextQueueContentRevision)
                        }
                    )
                savedArtworkUri?.let(updatedBuilder::setArtworkUri)
                val updated = updatedBuilder.build()
                // Preserve the source URI, position, playWhenReady and shuffle/repeat traversal.
                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updated).build())
                changed = true
            }
        }
        if (changed) {
            queueContentRevision = nextQueueContentRevision
            queueContentRevisionGeneration = player.queueGenerationOrNull()
            mediaSession?.let(::updateSessionExtras)
            updateSystemUiLyricMetadata(player, systemLyricSnapshot)
            mediaSession?.takeIf { player.currentMediaItem != null }?.let {
                onUpdateNotification(it, isPlaybackOngoing())
            }
        }
    }

    private fun ensureQueueIndex(player: Player) {
        val generation = player.queueGenerationOrNull()
        if (generation == indexedQueueGeneration && player.mediaItemCount == indexedQueueSize) return
        val mutableIndices = mutableMapOf<Long, MutableList<Int>>()
        for (index in 0 until player.mediaItemCount) {
            val songId = player.getMediaItemAt(index).mediaId.toLongOrNull() ?: continue
            mutableIndices.getOrPut(songId, ::mutableListOf) += index
        }
        queueIndicesBySongId = mutableIndices
        indexedQueueGeneration = generation
        indexedQueueSize = player.mediaItemCount
    }

    private fun Player.queueGenerationOrNull(): Long? =
        currentMediaItem?.mediaMetadata?.extras
            ?.takeIf { it.containsKey(EXTRA_QUEUE_GENERATION) }
            ?.getLong(EXTRA_QUEUE_GENERATION)

    private fun applyPlaybackMode(
        player: ExoPlayer,
        mode: PlaybackMode,
    ): ListenableFuture<SessionResult> {
        val requestId = playbackModeRequestId.incrementAndGet()
        if (mode != PlaybackMode.RANDOM) {
            val shuffleOrder = if (mode == PlaybackMode.REVERSE) {
                ReverseShuffleOrder(player.mediaItemCount)
            } else {
                null
            }
            applyPlaybackSettings(player, mode, shuffleOrder)
            commitPlaybackMode(mode)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        val itemCount = player.mediaItemCount
        val result = SettableFuture.create<SessionResult>()
        serviceScope.launch {
            try {
                // DefaultShuffleOrder builds two O(n) arrays. Generate them off the UI thread
                // so switching a large local library does not freeze controls or vinyl motion.
                val shuffleOrder = withContext(Dispatchers.Default) {
                    ShuffleOrder.DefaultShuffleOrder(itemCount)
                }
                if (requestId != playbackModeRequestId.get() || player.mediaItemCount != itemCount) {
                    result.set(SessionResult(SessionError.INFO_CANCELLED))
                    return@launch
                }
                applyPlaybackSettings(player, mode, shuffleOrder)
                commitPlaybackMode(mode)
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (error: CancellationException) {
                result.cancel(false)
                throw error
            } catch (_: Exception) {
                result.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return result
    }

    private fun applyPlaybackSettings(
        player: ExoPlayer,
        mode: PlaybackMode,
        shuffleOrder: ShuffleOrder?,
    ) {
        // Never replace, prepare, pause or seek the active playlist while changing mode.
        shuffleOrder?.let(player::setShuffleOrder)
        player.shuffleModeEnabled = mode == PlaybackMode.RANDOM || mode == PlaybackMode.REVERSE
        player.repeatMode = when (repeatForMode(mode)) {
            QueueRepeat.ALL -> Player.REPEAT_MODE_ALL
            QueueRepeat.ONE -> Player.REPEAT_MODE_ONE
            QueueRepeat.NONE -> Player.REPEAT_MODE_OFF
        }
    }

    private fun commitPlaybackMode(mode: PlaybackMode) {
        playbackMode = mode
        preferences.edit { putString(ARG_PLAYBACK_MODE, mode.name) }
        mediaSession?.let(::updateSessionExtras)
    }

    private fun applyAudioMixing(
        player: ExoPlayer,
        enabled: Boolean,
    ): ListenableFuture<SessionResult> {
        if (audioMixingEnabled == enabled) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        val resumeInterruptedPlayback = enabled &&
            resumeAfterCompetingPlayback &&
            player.currentMediaItem?.mediaId == interruptedMediaId &&
            !player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

        audioMixingEnabled = enabled
        if (enabled) cancelInterruptedPlaybackResume()
        player.setAudioAttributes(playbackAudioAttributes, !enabled)
        preferences.edit { putBoolean(ARG_AUDIO_MIXING_ENABLED, enabled) }
        mediaSession?.let(::updateSessionExtras)
        if (resumeInterruptedPlayback) player.play()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun updateSessionExtras(session: MediaSession) {
        session.setSessionExtras(
            playbackModeBundle(playbackMode).apply {
                putBoolean(ARG_AUDIO_MIXING_ENABLED, audioMixingEnabled)
                putLong(EXTRA_QUEUE_CONTENT_REVISION, queueContentRevision)
                queueContentRevisionGeneration?.let {
                    putLong(EXTRA_QUEUE_GENERATION, it)
                }
                putString(
                    SystemLyricsSessionContract.EXTRA_CURRENT_LINE,
                    systemLyricSnapshot.currentLine,
                )
                putString(SystemLyricsSessionContract.EXTRA_NEXT_LINE, systemLyricSnapshot.nextLine)
                putString(
                    SystemLyricsSessionContract.EXTRA_AUDIO_OUTPUT_ROUTE,
                    systemLyricSnapshot.outputRoute.name,
                )
                putLong(
                    SystemLyricsSessionContract.EXTRA_APPLIED_DELAY_MS,
                    systemLyricSnapshot.appliedDelayMs,
                )
                putLong(
                    SystemLyricsSessionContract.EXTRA_CONTENT_REVISION,
                    systemLyricSnapshot.contentRevision,
                )
            },
        )
    }

    /**
     * System media surfaces read the current MediaSession item on every supported API level.
     * Updating displayTitle/subtitle gives pre-Android 13 lock screens the same best-effort metadata
     * as the Android 13+ media card. title/artist/album remain untouched, and the original display
     * fields are restored when lyrics should no longer be exposed.
     */
    private fun updateSystemUiLyricMetadata(player: Player, snapshot: SystemLyricSnapshot) {
        var item = player.currentMediaItem
        var index = player.currentMediaItemIndex
        if (item == null || index == C.INDEX_UNSET) {
            restoreSystemUiLyricMetadata(player)
            return
        }
        if (systemUiMetadataBackup?.mediaId != null &&
            systemUiMetadataBackup?.mediaId != item.mediaId
        ) {
            restoreSystemUiLyricMetadata(player)
            item = player.currentMediaItem ?: return
            index = player.currentMediaItemIndex
            if (index == C.INDEX_UNSET) return
        }
        val metadata = item.mediaMetadata
        val lyric = snapshot.currentLine.takeIf {
            systemLyricsSettings.notificationLyricsEnabled &&
                snapshot.songId?.toString() == item.mediaId &&
                it.isNotBlank()
        }
        if (lyric == null) {
            restoreSystemUiLyricMetadata(player)
            return
        }
        if (systemUiMetadataBackup == null) {
            systemUiMetadataBackup = SystemUiMetadataBackup(
                mediaId = item.mediaId,
                displayTitle = metadata.displayTitle,
                subtitle = metadata.subtitle,
            )
        }
        val secondary = listOfNotNull(
            metadata.title?.toString()?.takeIf(String::isNotBlank),
            metadata.artist?.toString()?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
        if (
            metadata.displayTitle?.toString() == lyric &&
            metadata.subtitle?.toString().orEmpty() == secondary
        ) return
        val updatedMetadata: MediaMetadata = metadata.buildUpon()
            .setDisplayTitle(lyric)
            .setSubtitle(secondary)
            .build()
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updatedMetadata).build())
    }

    private fun restoreSystemUiLyricMetadata(player: Player) {
        val backup = systemUiMetadataBackup ?: return
        systemUiMetadataBackup = null
        val index = (0 until player.mediaItemCount).firstOrNull { itemIndex ->
            player.getMediaItemAt(itemIndex).mediaId == backup.mediaId
        } ?: return
        val item = player.getMediaItemAt(index)
        val metadata = item.mediaMetadata
        if (
            metadata.displayTitle?.toString() == backup.displayTitle?.toString() &&
            metadata.subtitle?.toString() == backup.subtitle?.toString()
        ) return
        val restored = metadata.buildUpon()
            .setDisplayTitle(backup.displayTitle)
            .setSubtitle(backup.subtitle)
            .build()
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(restored).build())
    }

    private data class SystemUiMetadataBackup(
        val mediaId: String,
        val displayTitle: CharSequence?,
        val subtitle: CharSequence?,
    )

    private companion object {
        const val PLAYBACK_PREFERENCES = "playback_preferences"
        const val RESUME_DELAY_MS = 500L
    }
}
