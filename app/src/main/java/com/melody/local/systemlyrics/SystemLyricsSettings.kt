package com.melody.local.systemlyrics

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persistent settings shared by the player, overlay and UI. Every setter is immediately durable.
 * Per-route manual values are added to the automatic route estimate; a positive value delays text.
 */
class SystemLyricsSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var overlayEnabled: Boolean
        get() = preferences.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_OVERLAY_ENABLED, value) }

    var notificationLyricsEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, value) }

    var automaticLatencyCompensationEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTOMATIC_LATENCY_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_AUTOMATIC_LATENCY_ENABLED, value) }

    fun manualDelayMs(route: AudioOutputRoute): Long = preferences
        .getLong(manualDelayKey(route), 0L)
        .coerceIn(LyricsTimingPolicy.MIN_MANUAL_DELAY_MS, LyricsTimingPolicy.MAX_MANUAL_DELAY_MS)

    fun setManualDelayMs(route: AudioOutputRoute, delayMs: Long) {
        preferences.edit {
            putLong(
                manualDelayKey(route),
                delayMs.coerceIn(
                    LyricsTimingPolicy.MIN_MANUAL_DELAY_MS,
                    LyricsTimingPolicy.MAX_MANUAL_DELAY_MS,
                ),
            )
        }
    }

    fun appliedDelayMs(route: AudioOutputRoute): Long = LyricsTimingPolicy.appliedDelayMs(
        route = route,
        manualDelayMs = manualDelayMs(route),
        automaticCompensationEnabled = automaticLatencyCompensationEnabled,
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun manualDelayKey(route: AudioOutputRoute): String =
        "$KEY_MANUAL_DELAY_PREFIX${route.name}"

    companion object {
        internal const val PREFERENCES_NAME = "system_lyrics_preferences"
        internal const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        internal const val KEY_NOTIFICATION_LYRICS_ENABLED = "notification_lyrics_enabled"
        internal const val KEY_AUTOMATIC_LATENCY_ENABLED = "automatic_latency_enabled"
        internal const val KEY_MANUAL_DELAY_PREFIX = "manual_delay_ms_"
    }
}
