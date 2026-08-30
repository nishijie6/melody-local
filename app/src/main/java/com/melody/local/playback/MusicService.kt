package com.melody.local.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

@OptIn(markerClass = [UnstableApi::class])
class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val modeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackModeRequestId = AtomicLong()
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
            return applyPlaybackMode(session.player as ExoPlayer, requestedMode)
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
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
            .also { it.setSessionExtras(playbackModeBundle(playbackMode)) }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        playbackModeRequestId.incrementAndGet()
        modeScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

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
        modeScope.launch {
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
        mediaSession?.setSessionExtras(playbackModeBundle(mode))
    }

    private companion object {
        const val PLAYBACK_PREFERENCES = "playback_preferences"
    }
}
