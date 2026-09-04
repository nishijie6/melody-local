package com.melody.local.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(markerClass = [UnstableApi::class])
class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
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
                !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            ) {
                rememberInterruptedPlayback(player)
            } else {
                cancelInterruptedPlaybackResume()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
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
    private var playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL

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
            if (customCommand.customAction != ACTION_SET_PLAYBACK_MODE) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val requestedMode = args.playbackModeOrNull()
                ?: return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                )
            applyPlaybackMode(session.player as ExoPlayer, requestedMode)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        playbackMode = preferences.getString(ARG_PLAYBACK_MODE, null)
            ?.let { stored -> runCatching { PlaybackMode.valueOf(stored) }.getOrNull() }
            ?: PlaybackMode.SEQUENTIAL
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
            .also { it.setSessionExtras(playbackModeBundle(playbackMode)) }
        audioManager.registerAudioPlaybackCallback(playbackActivityCallback, mainHandler)
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
        cancelInterruptedPlaybackResume()
        audioManager.unregisterAudioPlaybackCallback(playbackActivityCallback)
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun rememberInterruptedPlayback(player: Player) {
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

    private fun applyPlaybackMode(player: ExoPlayer, mode: PlaybackMode) {
        when (mode) {
            PlaybackMode.RANDOM -> {
                player.setShuffleOrder(DefaultShuffleOrder(player.mediaItemCount))
                player.shuffleModeEnabled = true
            }
            PlaybackMode.REVERSE -> {
                player.setShuffleOrder(ReverseShuffleOrder(player.mediaItemCount))
                player.shuffleModeEnabled = true
            }
            PlaybackMode.SEQUENTIAL, PlaybackMode.LOOP, PlaybackMode.SINGLE -> {
                player.shuffleModeEnabled = false
            }
        }
        player.repeatMode = when (repeatForMode(mode)) {
            QueueRepeat.ALL -> Player.REPEAT_MODE_ALL
            QueueRepeat.ONE -> Player.REPEAT_MODE_ONE
            QueueRepeat.NONE -> Player.REPEAT_MODE_OFF
        }
        playbackMode = mode
        preferences.edit { putString(ARG_PLAYBACK_MODE, mode.name) }
        mediaSession?.setSessionExtras(playbackModeBundle(mode))
    }

    private companion object {
        const val PLAYBACK_PREFERENCES = "playback_preferences"
        const val RESUME_DELAY_MS = 500L
    }
}
