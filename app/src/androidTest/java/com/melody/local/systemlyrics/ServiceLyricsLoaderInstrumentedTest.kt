package com.melody.local.systemlyrics

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.Song
import com.melody.local.lyrics.AutomaticLyricsResolver
import com.melody.local.lyrics.LyricLine
import com.melody.local.lyrics.LyricsOrigin
import com.melody.local.lyrics.LyricsResolution
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceLyricsLoaderInstrumentedTest {
    @Test
    fun cachedLyricsSkipAutomaticDiscovery() = runBlocking {
        val cached = lyrics("cached")
        var resolverCalls = 0
        val loader = ServiceLyricsLoader(
            store = FakeStore(cached),
            resolver = AutomaticLyricsResolver {
                resolverCalls++
                LyricsResolution.NoResults
            },
        )

        assertSame(cached, loader.load(song()))
        assertEquals(0, resolverCalls)
    }

    @Test
    fun missingLyricsAreDiscoveredFromServiceScope() = runBlocking {
        val discovered = lyrics("background")
        var resolverCalls = 0
        val loader = ServiceLyricsLoader(
            store = FakeStore(null),
            resolver = AutomaticLyricsResolver {
                resolverCalls++
                LyricsResolution.Applied(discovered, LyricsOrigin.EMBEDDED_TAG)
            },
        )

        assertSame(discovered, loader.load(song()))
        assertEquals(1, resolverCalls)
    }

    private class FakeStore(private val cached: ParsedLyrics?) : LyricsStore {
        override suspend fun load(songId: Long): ParsedLyrics? = cached
        override suspend fun import(songId: Long, uri: Uri): ParsedLyrics = error("unused")
        override suspend fun delete(songId: Long) = Unit
        override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
    }

    private fun lyrics(text: String) = ParsedLyrics(
        lines = listOf(LyricLine(0L, text)),
        isSynced = true,
    )

    private fun song() = Song(
        id = 7L,
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        trackNumber = 0,
        dateAddedSeconds = 0L,
        contentUri = Uri.parse("content://audio/7"),
        albumArtUri = null,
    )
}
