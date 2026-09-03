package com.melody.local.lyrics.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsDiscoveryCoordinatorTest {
    private val track = LyricsTrack(1L, "Title", "Artist", "Album", 120_000L, "Title.mp3")

    @Test
    fun `high confidence local lyric prevents any network request`() = runBlocking {
        var onlineCalls = 0
        val local = LocalLyricsSource {
            LocalLyricsLookup.Found(
                best = RankedLocalLyrics(
                    candidate = LocalLyricsCandidate("file:///Title.lrc", "Title.lrc"),
                    score = 100,
                    reason = LocalMatchReason.SAME_AUDIO_FILE_NAME,
                    canAutoImport = true,
                ),
                alternatives = emptyList(),
            )
        }
        val online = object : OnlineLyricsSource {
            override suspend fun search(request: LyricsSearchRequest): RemoteLyricsResult<List<RankedOnlineLyrics>> {
                onlineCalls++
                return RemoteLyricsResult.NoResults
            }

            override suspend fun download(recordId: Long) = RemoteLyricsResult.NoResults
        }

        val result = LyricsDiscoveryCoordinator(listOf(local), online).discover(track)

        assertTrue(result is LyricsDiscoveryResult.LocalMatch)
        assertEquals(0, onlineCalls)
    }

    @Test
    fun `local miss falls back online and recommends only auto match`() = runBlocking {
        val record = LrclibLyricsRecord(
            id = 2L,
            trackName = "Title",
            artistName = "Artist",
            albumName = "Album",
            durationSeconds = 120.0,
            instrumental = false,
            plainLyrics = "text",
            syncedLyrics = "[00:01.00]text",
        )
        val match = RankedOnlineLyrics(record, 100, true)
        val online = object : OnlineLyricsSource {
            override suspend fun search(request: LyricsSearchRequest) =
                RemoteLyricsResult.Success(listOf(match))

            override suspend fun download(recordId: Long) = RemoteLyricsResult.Success(record)
        }

        val result = LyricsDiscoveryCoordinator(
            localSources = listOf(LocalLyricsSource { LocalLyricsLookup.NoMatch(3) }),
            onlineSource = online,
        ).discover(track)

        assertTrue(result is LyricsDiscoveryResult.OnlineMatches)
        assertEquals(match, (result as LyricsDiscoveryResult.OnlineMatches).recommended)
    }

    @Test
    fun `offline mode never touches online source`() = runBlocking {
        var onlineCalls = 0
        val online = object : OnlineLyricsSource {
            override suspend fun search(request: LyricsSearchRequest): RemoteLyricsResult<List<RankedOnlineLyrics>> {
                onlineCalls++
                return RemoteLyricsResult.NoResults
            }

            override suspend fun download(recordId: Long) = RemoteLyricsResult.NoResults
        }

        val result = LyricsDiscoveryCoordinator(emptyList(), online).discover(track, allowOnline = false)

        assertTrue(result is LyricsDiscoveryResult.NoResults)
        assertEquals(0, onlineCalls)
    }
}
