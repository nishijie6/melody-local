package com.melody.local.lyrics

import android.content.Context

/**
 * Process-wide production lyric services shared by the UI controller and playback service.
 * Sharing one resolver prevents duplicate local scans and LRCLIB requests when both surfaces react
 * to the same song transition.
 */
internal class AppLyricsRuntime private constructor(context: Context) {
    val repository = LyricsRepository(context.applicationContext)
    val resolver = LyricsResolver(context.applicationContext, repository)

    companion object {
        @Volatile
        private var instance: AppLyricsRuntime? = null

        fun get(context: Context): AppLyricsRuntime = instance ?: synchronized(this) {
            instance ?: AppLyricsRuntime(context.applicationContext).also { instance = it }
        }
    }
}
