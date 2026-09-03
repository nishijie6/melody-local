package com.melody.local.systemlyrics

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Entry points intended for a settings screen. Enabling never bypasses Android's overlay consent. */
object FloatingLyricsController {
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Latest route and applied delay reported by the playback service in this app process. */
    fun latestSnapshot(): SystemLyricSnapshot = SystemLyricsRuntime.latestSnapshot

    fun permissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

    /**
     * Returns false when Android overlay permission is still missing. The caller should launch
     * [permissionIntent], then call this method again after the user returns.
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val settings = SystemLyricsSettings(appContext)
        if (enabled && !canDrawOverlays(appContext)) {
            settings.overlayEnabled = false
            return false
        }
        settings.overlayEnabled = enabled
        val action = if (enabled) {
            FloatingLyricsService.ACTION_SHOW
        } else {
            FloatingLyricsService.ACTION_CLOSE
        }
        val intent = Intent(appContext, FloatingLyricsService::class.java).setAction(action)
        if (enabled) {
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
                .onFailure {
                    // Android can reject a foreground-service start from a background context.
                    settings.overlayEnabled = false
                    return false
                }
        } else appContext.stopService(intent)
        return true
    }

    fun refreshIfEnabled(context: Context): Boolean {
        val settings = SystemLyricsSettings(context)
        return !settings.overlayEnabled || setEnabled(context, true)
    }
}
