package com.melody.local.systemlyrics

import com.melody.local.data.Song
import com.melody.local.lyrics.AutomaticLyricsResolver
import com.melody.local.lyrics.LyricsResolution
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics

/** Loads cached lyrics first, then performs automatic discovery from the playback-service scope. */
internal class ServiceLyricsLoader(
    private val store: LyricsStore,
    private val resolver: AutomaticLyricsResolver,
) {
    suspend fun load(song: Song): ParsedLyrics? {
        store.load(song.id)?.let { return it }
        return (resolver.resolveAutomatically(song) as? LyricsResolution.Applied)?.lyrics
    }
}
