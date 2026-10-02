package com.melody.local.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.melody.local.data.Song
import com.melody.local.data.playbackRange
import com.melody.local.systemlyrics.AudioOutputRoute
import com.melody.local.systemlyrics.SystemLyricsSessionContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PlaybackQueueSourceType { LIBRARY, PLAYLIST }

data class PlaybackQueueSource(
    val type: PlaybackQueueSourceType,
    val name: String,
    val playlistId: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "queue source name must not be blank" }
        require(
            (type == PlaybackQueueSourceType.LIBRARY && playlistId == null) ||
                (type == PlaybackQueueSourceType.PLAYLIST && playlistId != null && playlistId > 0L)
        ) { "queue source type and playlist ID do not match" }
    }

    companion object {
        fun library(name: String = "全部歌曲") = PlaybackQueueSource(
            type = PlaybackQueueSourceType.LIBRARY,
            name = name.ifBlank { "全部歌曲" },
        )

        fun playlist(id: Long, name: String) = PlaybackQueueSource(
            type = PlaybackQueueSourceType.PLAYLIST,
            name = name.ifBlank { "歌单" },
            playlistId = id,
        )
    }
}

data class PlaybackQueueItem(
    val mediaId: Long?,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
)

data class PlaybackUiState(
    val mediaId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Offset of the clipped Media3 timeline in the original source file. */
    val playbackStartMs: Long = 0L,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val audioMixingEnabled: Boolean = false,
    val lyricDelayMs: Long = 0L,
    val lyricsContentRevision: Long = 0L,
    val audioOutputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN,
    val queueSource: PlaybackQueueSource? = null,
    val queueItems: List<PlaybackQueueItem> = emptyList(),
    val queueIndex: Int = -1,
) {
    val sourcePositionMs: Long get() = (playbackStartMs + positionMs).coerceAtLeast(0L)
}

interface PlaybackController {
    val state: StateFlow<PlaybackUiState>
    fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        source: PlaybackQueueSource = PlaybackQueueSource.library(),
    )
    fun togglePlayPause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekToNext()
    fun seekToPrevious()
    fun seekToQueueItem(index: Int)
    fun setPlaybackMode(mode: PlaybackMode)
    fun setAudioMixingEnabled(enabled: Boolean)
    fun release()
}

@OptIn(markerClass = [UnstableApi::class])
class PlayerConnection(context: Context) :
    PlaybackController,
    Player.Listener,
    MediaController.Listener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var playbackModeCommandId = 0L
    private var audioMixingCommandId = 0L
    private var released = false
    private var currentPlaybackMode: PlaybackMode = PlaybackMode.SEQUENTIAL
    private var currentAudioMixingEnabled = false
    private var currentLyricDelayMs: Long = 0L
    private var currentLyricsContentRevision: Long = 0L
    private var currentAudioOutputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN
    private var currentQueueContentRevision = 0L
    private var pendingQueueContentRevision: Long? = null
    private var pendingQueueContentGeneration: Long? = null
    private var currentQueueSource: PlaybackQueueSource? = null
    private var currentQueueItems: List<PlaybackQueueItem> = emptyList()

    private val _state = MutableStateFlow(PlaybackUiState(playbackMode = currentPlaybackMode))
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    init {
        connect()
    }

    private fun connect() {
        if (released || controllerFuture != null) return
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, MusicService::class.java),
        )
        val future = MediaController.Builder(appContext, sessionToken)
            .setListener(this)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connectedController ->
                        if (released || controllerFuture !== future) {
                            MediaController.releaseFuture(future)
                            return@onSuccess
                        }
                        controller = connectedController
                        reconnectAttempts = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                        connectedController.addListener(this)
                        updateSessionExtras(connectedController.sessionExtras)
                        while (pendingActions.isNotEmpty()) {
                            pendingActions.removeFirst()(connectedController)
                        }
                        updateState(refreshQueue = true)
                    }
                    .onFailure {
                        if (controllerFuture === future) controllerFuture = null
                        scheduleReconnect()
                    }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    override fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        source: PlaybackQueueSource,
    ) {
        if (songs.isEmpty()) return
        withController { player ->
            val safeStartIndex = startIndex.coerceIn(songs.indices)
            val queueGeneration = SystemClock.elapsedRealtimeNanos()
            player.setMediaItems(
                songs.map { it.asMediaItem(source, queueGeneration) },
                safeStartIndex,
                0L,
            )
            updateState(refreshQueue = true)
            // Reapply the service-owned policy after replacing the queue. This is especially
            // important when an asynchronous random-order request was created for the old size.
            sendPlaybackMode(player, currentPlaybackMode)
            player.prepare()
            player.play()
        }
    }

    override fun togglePlayPause() = withController { player ->
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    override fun stop() {
        withController { player ->
            player.stop()
            player.clearMediaItems()
            updateState(refreshQueue = true)
        }
    }

    override fun seekTo(positionMs: Long) = withController { player ->
        val duration = player.safeDuration()
        player.seekTo(positionMs.coerceIn(0L, duration.coerceAtLeast(0L)))
        updateState()
    }

    override fun seekToNext() = withController { it.seekToNextMediaItem() }

    override fun seekToPrevious() = withController { player ->
        if (player.currentPosition > 5_000L) player.seekTo(0L) else player.seekToPreviousMediaItem()
    }

    override fun seekToQueueItem(index: Int) = withController { player ->
        if (index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) {
            return@withController
        }
        player.seekToDefaultPosition(index)
        updateState()
    }

    override fun setPlaybackMode(mode: PlaybackMode) {
        if (mode == currentPlaybackMode) return
        currentPlaybackMode = mode
        _state.value = _state.value.copy(playbackMode = mode)
        withController { player -> sendPlaybackMode(player, mode) }
    }

    override fun setAudioMixingEnabled(enabled: Boolean) {
        if (enabled == currentAudioMixingEnabled) return
        currentAudioMixingEnabled = enabled
        _state.value = _state.value.copy(audioMixingEnabled = enabled)
        withController { player -> sendAudioMixing(player, enabled) }
    }

    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
        if (released || this.controller !== controller) return
        updateSessionExtras(extras)
    }

    override fun onDisconnected(controller: MediaController) {
        if (released || this.controller !== controller) return
        playbackModeCommandId++
        audioMixingCommandId++
        val disconnectedFuture = controllerFuture
        controller.removeListener(this)
        progressJob?.cancel()
        progressJob = null
        _state.value = _state.value.copy(isPlaying = false)
        this.controller = null
        controllerFuture = null
        if (disconnectedFuture != null) {
            MediaController.releaseFuture(disconnectedFuture)
        } else {
            controller.release()
        }
        scheduleReconnect()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
        val pendingContentIsVisible = timelineChanged && consumeVisibleQueueContentRevision(player)
        updateState(
            refreshQueue = timelineChanged &&
                (pendingContentIsVisible || shouldRefreshQueueSnapshot(player)),
        )
    }

    private fun consumeVisibleQueueContentRevision(player: Player): Boolean {
        val expected = pendingQueueContentRevision ?: return false
        val expectedGeneration = pendingQueueContentGeneration
        if (expectedGeneration != null && player.queueGenerationOrNull() != expectedGeneration) {
            pendingQueueContentRevision = null
            pendingQueueContentGeneration = null
            return false
        }
        val visible = (0 until player.mediaItemCount).any { index ->
            player.getMediaItemAt(index).mediaMetadata.extras
                ?.takeIf { it.containsKey(EXTRA_QUEUE_CONTENT_REVISION) }
                ?.getLong(EXTRA_QUEUE_CONTENT_REVISION)
                ?.let { it >= expected } == true
        }
        if (visible) {
            pendingQueueContentRevision = null
            pendingQueueContentGeneration = null
        }
        return visible
    }

    private fun shouldRefreshQueueSnapshot(player: Player): Boolean {
        if (currentQueueItems.size != player.mediaItemCount) return true
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem
        if (item == null || index !in currentQueueItems.indices) return currentQueueItems.isNotEmpty()
        if (item.queueSourceOrNull() != currentQueueSource) return true
        if (item.asQueueItem() != currentQueueItems[index]) return true
        return false
    }

    private fun updateState(refreshQueue: Boolean = false) {
        val player = controller ?: return
        if (refreshQueue || currentQueueItems.size != player.mediaItemCount) {
            refreshQueueSnapshot(player)
        }
        val metadata = player.mediaMetadata
        _state.value = PlaybackUiState(
            mediaId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkUri = metadata.artworkUri,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.safeDuration(),
            playbackStartMs = player.currentMediaItem?.mediaMetadata?.extras
                ?.playbackStartMsOrZero()
                ?: 0L,
            playbackMode = currentPlaybackMode,
            audioMixingEnabled = currentAudioMixingEnabled,
            lyricDelayMs = currentLyricDelayMs,
            lyricsContentRevision = currentLyricsContentRevision,
            audioOutputRoute = currentAudioOutputRoute,
            queueSource = currentQueueSource,
            queueItems = currentQueueItems,
            queueIndex = player.currentMediaItemIndex.takeIf {
                player.currentMediaItem != null && it in currentQueueItems.indices
            } ?: -1,
        )
        updateProgressLoop(player.isPlaying)
    }

    private fun refreshQueueSnapshot(player: Player) {
        currentQueueItems = List(player.mediaItemCount) { index ->
            player.getMediaItemAt(index).asQueueItem()
        }
        currentQueueSource = player.currentMediaItem?.queueSourceOrNull()
            ?: (0 until player.mediaItemCount)
                .firstNotNullOfOrNull { index -> player.getMediaItemAt(index).queueSourceOrNull() }
        consumeVisibleQueueContentRevision(player)
    }

    private fun updateProgressLoop(isPlaying: Boolean) {
        if (!isPlaying) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                delay(400L)
                updateState()
            }
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val activeController = controller
        if (activeController != null) {
            action(activeController)
        } else {
            if (pendingActions.size >= MAX_PENDING_ACTIONS) pendingActions.removeFirst()
            pendingActions.addLast(action)
            connect()
        }
    }

    override fun release() {
        released = true
        playbackModeCommandId++
        audioMixingCommandId++
        progressJob?.cancel()
        reconnectJob?.cancel()
        pendingActions.clear()
        controller?.removeListener(this)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        controllerFuture = null
        scope.cancel()
    }

    private fun scheduleReconnect() {
        if (released || reconnectJob?.isActive == true) return
        val delayMillis = 500L * (1L shl reconnectAttempts.coerceAtMost(4))
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delayMillis)
            reconnectJob = null
            connect()
        }
    }

    private fun Player.safeDuration(): Long =
        duration.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L

    private fun sendPlaybackMode(player: MediaController, mode: PlaybackMode) {
        val commandId = ++playbackModeCommandId
        val requestedMode = mode
        val future = player.sendCustomCommand(
            SET_PLAYBACK_MODE_COMMAND,
            playbackModeBundle(requestedMode),
        )
        future.addListener(
            {
                val result = runCatching { future.get() }.getOrNull()
                if (
                    !released &&
                    controller === player &&
                    commandId == playbackModeCommandId &&
                    currentPlaybackMode == requestedMode &&
                    result?.resultCode != SessionResult.RESULT_SUCCESS
                ) {
                    updateSessionExtras(player.sessionExtras)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun sendAudioMixing(player: MediaController, enabled: Boolean) {
        val commandId = ++audioMixingCommandId
        val requestedValue = enabled
        val future = player.sendCustomCommand(
            SET_AUDIO_MIXING_COMMAND,
            audioMixingBundle(requestedValue),
        )
        future.addListener(
            {
                val result = runCatching { future.get() }.getOrNull()
                if (
                    !released &&
                    controller === player &&
                    commandId == audioMixingCommandId &&
                    currentAudioMixingEnabled == requestedValue &&
                    result?.resultCode != SessionResult.RESULT_SUCCESS
                ) {
                    updateSessionExtras(player.sessionExtras)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun updateSessionExtras(extras: Bundle) {
        val queueContentChanged = extras.containsKey(EXTRA_QUEUE_CONTENT_REVISION) &&
            extras.getLong(EXTRA_QUEUE_CONTENT_REVISION) != currentQueueContentRevision
        if (extras.containsKey(EXTRA_QUEUE_CONTENT_REVISION)) {
            currentQueueContentRevision = extras.getLong(EXTRA_QUEUE_CONTENT_REVISION)
            if (queueContentChanged) {
                pendingQueueContentRevision = currentQueueContentRevision.takeIf { it > 0L }
                pendingQueueContentGeneration = extras
                    .takeIf { it.containsKey(EXTRA_QUEUE_GENERATION) }
                    ?.getLong(EXTRA_QUEUE_GENERATION)
            }
        }
        extras.playbackModeOrNull()?.let { currentPlaybackMode = it }
        extras.audioMixingEnabledOrNull()?.let { currentAudioMixingEnabled = it }
        currentLyricDelayMs = extras.getLong(
            SystemLyricsSessionContract.EXTRA_APPLIED_DELAY_MS,
            currentLyricDelayMs,
        )
        currentLyricsContentRevision = extras.getLong(
            SystemLyricsSessionContract.EXTRA_CONTENT_REVISION,
            currentLyricsContentRevision,
        )
        currentAudioOutputRoute = extras.getString(
            SystemLyricsSessionContract.EXTRA_AUDIO_OUTPUT_ROUTE,
        )?.let { stored -> runCatching { AudioOutputRoute.valueOf(stored) }.getOrNull() }
            ?: currentAudioOutputRoute
        val pendingContentIsVisible = queueContentChanged &&
            controller?.let(::consumeVisibleQueueContentRevision) == true
        if (pendingContentIsVisible && currentQueueItems.isNotEmpty()) {
            updateState(refreshQueue = true)
        } else {
            _state.value = _state.value.copy(
                playbackMode = currentPlaybackMode,
                audioMixingEnabled = currentAudioMixingEnabled,
                lyricDelayMs = currentLyricDelayMs,
                lyricsContentRevision = currentLyricsContentRevision,
                audioOutputRoute = currentAudioOutputRoute,
            )
        }
    }

    private fun Song.asMediaItem(
        source: PlaybackQueueSource,
        queueGeneration: Long,
    ): MediaItem {
        val playbackRange = playbackRange()
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setExtras(
                source.asMediaMetadataExtras(queueGeneration).apply {
                    putPlaybackRange(playbackRange, durationMs)
                }
            )
        albumArtUri?.let(metadataBuilder::setArtworkUri)
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri)
            .setMediaMetadata(metadataBuilder.build())
            .setClippingConfiguration(playbackRange.asClippingConfiguration(durationMs))
            .build()
    }

    private fun MediaItem.asQueueItem() = PlaybackQueueItem(
        mediaId = mediaId.toLongOrNull(),
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "未知歌曲" },
        artist = mediaMetadata.artist?.toString().orEmpty().ifBlank { "未知歌手" },
        artworkUri = mediaMetadata.artworkUri,
    )

    private fun PlaybackQueueSource.asMediaMetadataExtras(queueGeneration: Long) = Bundle().apply {
        putString(EXTRA_QUEUE_SOURCE_TYPE, type.name)
        putString(EXTRA_QUEUE_SOURCE_NAME, name)
        putLong(EXTRA_QUEUE_GENERATION, queueGeneration)
        playlistId?.let { putLong(EXTRA_QUEUE_PLAYLIST_ID, it) }
    }

    private fun MediaItem.queueSourceOrNull(): PlaybackQueueSource? {
        val extras = mediaMetadata.extras ?: return null
        val type = extras.getString(EXTRA_QUEUE_SOURCE_TYPE)
            ?.let { stored -> runCatching { PlaybackQueueSourceType.valueOf(stored) }.getOrNull() }
            ?: return null
        val name = extras.getString(EXTRA_QUEUE_SOURCE_NAME).orEmpty()
        if (name.isBlank()) return null
        if (
            type == PlaybackQueueSourceType.LIBRARY &&
            extras.containsKey(EXTRA_QUEUE_PLAYLIST_ID)
        ) return null
        return runCatching {
            when (type) {
                PlaybackQueueSourceType.LIBRARY -> PlaybackQueueSource.library(name)
                PlaybackQueueSourceType.PLAYLIST -> {
                    if (!extras.containsKey(EXTRA_QUEUE_PLAYLIST_ID)) return null
                    PlaybackQueueSource.playlist(extras.getLong(EXTRA_QUEUE_PLAYLIST_ID), name)
                }
            }
        }.getOrNull()
    }

    private fun Player.queueGenerationOrNull(): Long? =
        currentMediaItem?.mediaMetadata?.extras
            ?.takeIf { it.containsKey(EXTRA_QUEUE_GENERATION) }
            ?.getLong(EXTRA_QUEUE_GENERATION)

    private companion object {
        const val MAX_PENDING_ACTIONS = 32
        const val EXTRA_QUEUE_SOURCE_TYPE = "com.melody.local.playback.QUEUE_SOURCE_TYPE"
        const val EXTRA_QUEUE_SOURCE_NAME = "com.melody.local.playback.QUEUE_SOURCE_NAME"
        const val EXTRA_QUEUE_PLAYLIST_ID = "com.melody.local.playback.QUEUE_PLAYLIST_ID"
    }
}
