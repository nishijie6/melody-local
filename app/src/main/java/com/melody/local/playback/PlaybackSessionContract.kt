package com.melody.local.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal const val ACTION_SET_PLAYBACK_MODE = "com.melody.local.playback.SET_MODE"
internal const val ARG_PLAYBACK_MODE = "playback_mode"
internal val SET_PLAYBACK_MODE_COMMAND = SessionCommand(ACTION_SET_PLAYBACK_MODE, Bundle.EMPTY)
internal const val ACTION_SET_AUDIO_MIXING = "com.melody.local.playback.SET_AUDIO_MIXING"
internal const val ARG_AUDIO_MIXING_ENABLED = "audio_mixing_enabled"
internal val SET_AUDIO_MIXING_COMMAND = SessionCommand(ACTION_SET_AUDIO_MIXING, Bundle.EMPTY)
internal const val EXTRA_QUEUE_GENERATION = "com.melody.local.playback.QUEUE_GENERATION"
internal const val EXTRA_QUEUE_CONTENT_REVISION =
    "com.melody.local.playback.QUEUE_CONTENT_REVISION"
internal const val EXTRA_SOURCE_DURATION_MS = "com.melody.local.playback.SOURCE_DURATION_MS"
internal const val EXTRA_PLAYBACK_START_MS = "com.melody.local.playback.PLAYBACK_START_MS"
internal const val EXTRA_PLAYBACK_END_MS = "com.melody.local.playback.PLAYBACK_END_MS"

internal fun playbackModeBundle(mode: PlaybackMode): Bundle = Bundle().apply {
    putString(ARG_PLAYBACK_MODE, mode.name)
}

internal fun Bundle.playbackModeOrNull(): PlaybackMode? =
    getString(ARG_PLAYBACK_MODE)?.let { stored ->
        runCatching { PlaybackMode.valueOf(stored) }.getOrNull()
    }

internal fun audioMixingBundle(enabled: Boolean): Bundle = Bundle().apply {
    putBoolean(ARG_AUDIO_MIXING_ENABLED, enabled)
}

internal fun Bundle.audioMixingEnabledOrNull(): Boolean? =
    if (containsKey(ARG_AUDIO_MIXING_ENABLED)) getBoolean(ARG_AUDIO_MIXING_ENABLED) else null

