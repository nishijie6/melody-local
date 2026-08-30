package com.melody.local.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal const val ACTION_SET_PLAYBACK_MODE = "com.melody.local.playback.SET_MODE"
internal const val ARG_PLAYBACK_MODE = "playback_mode"
internal val SET_PLAYBACK_MODE_COMMAND = SessionCommand(ACTION_SET_PLAYBACK_MODE, Bundle.EMPTY)

internal fun playbackModeBundle(mode: PlaybackMode): Bundle = Bundle().apply {
    putString(ARG_PLAYBACK_MODE, mode.name)
}

internal fun Bundle.playbackModeOrNull(): PlaybackMode? =
    getString(ARG_PLAYBACK_MODE)?.let { stored ->
        runCatching { PlaybackMode.valueOf(stored) }.getOrNull()
    }

