package com.melody.local.lyrics.discovery

import com.melody.local.data.Song

fun Song.toLyricsTrack(sourceFileName: String? = null): LyricsTrack = LyricsTrack(
    songId = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourceFileName = sourceFileName,
)
