package com.melody.local.systemlyrics

/** Read-only MediaSession extras exposed to controllers, System UI integrations and tests. */
object SystemLyricsSessionContract {
    const val EXTRA_CURRENT_LINE = "com.melody.local.extra.CURRENT_LYRIC"
    const val EXTRA_NEXT_LINE = "com.melody.local.extra.NEXT_LYRIC"
    const val EXTRA_AUDIO_OUTPUT_ROUTE = "com.melody.local.extra.AUDIO_OUTPUT_ROUTE"
    const val EXTRA_APPLIED_DELAY_MS = "com.melody.local.extra.LYRIC_DELAY_MS"
    const val EXTRA_CONTENT_REVISION = "com.melody.local.extra.LYRICS_CONTENT_REVISION"
}
