package com.melody.local.lyrics.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class LrclibLyricsSourceTest {
    private val track = LyricsTrack(
        songId = 8L,
        title = "I Want to Live",
        artist = "Borislav Slavov",
        album = "Baldur's Gate 3",
        durationMs = 233_000L,
        sourceFileName = "I Want to Live.flac",
    )

    @Test
    fun `search uses structured encoded query identifying header and parses records`() = runBlocking {
        val transport = QueueTransport(
            LyricsHttpResponse(200, "[$RECORD_JSON]"),
        )
        val source = source(transport)

        val result = source.search(LyricsSearchRequest(track))

        assertTrue(result is RemoteLyricsResult.Success)
        val records = (result as RemoteLyricsResult.Success).value
        assertEquals(1, records.size)
        assertEquals(3396226L, records.single().record.id)
        assertEquals("[00:17.12] I feel your breath", records.single().record.preferredLyrics)
        assertTrue(transport.urls.single().contains("track_name=I+Want+to+Live"))
        assertTrue(transport.urls.single().contains("artist_name=Borislav+Slavov"))
        assertTrue(transport.urls.single().contains("album_name=Baldur%27s+Gate+3"))
        assertTrue(transport.headers.single()["User-Agent"].orEmpty().startsWith("Yinlan/"))
    }

    @Test
    fun `keyword search uses q and unknown metadata is omitted from structured search`() = runBlocking {
        val keywordTransport = QueueTransport(LyricsHttpResponse(200, "[]"))
        source(keywordTransport).search(LyricsSearchRequest(track, keywords = "still alive portal"))
        assertTrue(keywordTransport.urls.single().endsWith("/search?q=still+alive+portal"))

        val structuredTransport = QueueTransport(LyricsHttpResponse(200, "[]"))
        source(structuredTransport).search(
            LyricsSearchRequest(track.copy(artist = "未知歌手", album = "未知专辑")),
        )
        assertTrue(structuredTransport.urls.single().contains("track_name="))
        assertFalse(structuredTransport.urls.single().contains("artist_name="))
        assertFalse(structuredTransport.urls.single().contains("album_name="))
    }

    @Test
    fun `empty search and missing download have explicit no-result state`() = runBlocking {
        val source = source(
            QueueTransport(
                LyricsHttpResponse(200, "[]"),
                LyricsHttpResponse(404, "{\"message\":\"not found\"}"),
            ),
        )

        assertEquals(RemoteLyricsResult.NoResults, source.search(LyricsSearchRequest(track)))
        assertEquals(RemoteLyricsResult.NoResults, source.download(999L))
    }

    @Test
    fun `download gets record by id and preserves plain fallback`() = runBlocking {
        val plainRecord = RECORD_JSON.replace(
            "\"syncedLyrics\":\"[00:17.12] I feel your breath\"",
            "\"syncedLyrics\":null",
        )
        val transport = QueueTransport(LyricsHttpResponse(200, plainRecord))

        val result = source(transport).download(3396226L)

        assertTrue(result is RemoteLyricsResult.Success)
        val record = (result as RemoteLyricsResult.Success).value
        assertEquals("I feel your breath", record.preferredLyrics)
        assertTrue(transport.urls.single().endsWith("/get/3396226"))
    }

    @Test
    fun `network exception and invalid response are distinct failure states`() = runBlocking {
        val network = LrclibLyricsSource(
            transport = LyricsHttpTransport { _, _ -> throw IOException("offline") },
            minimumRequestIntervalMs = 0L,
        ).search(LyricsSearchRequest(track))
        assertTrue(network is RemoteLyricsResult.NetworkFailure)

        val malformed = source(QueueTransport(LyricsHttpResponse(200, "not-json")))
            .search(LyricsSearchRequest(track))
        assertTrue(malformed is RemoteLyricsResult.ServiceFailure)
    }

    @Test
    fun `rate limit is surfaced and Retry-After delays following request`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val transport = QueueTransport(
            LyricsHttpResponse(429, "{}", mapOf("retry-after" to listOf("3"))),
            LyricsHttpResponse(200, RECORD_JSON),
        )
        val source = LrclibLyricsSource(
            transport = transport,
            minimumRequestIntervalMs = 300L,
            nowMs = { now },
            waitMs = { duration ->
                waits += duration
                now += duration
            },
        )

        val limited = source.search(LyricsSearchRequest(track))
        val downloaded = source.download(3396226L)

        assertEquals(RemoteLyricsResult.RateLimited(3), limited)
        assertTrue(downloaded is RemoteLyricsResult.Success)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `service error exposes server message`() = runBlocking {
        val result = source(
            QueueTransport(LyricsHttpResponse(503, "{\"message\":\"maintenance\"}")),
        ).search(LyricsSearchRequest(track))

        assertTrue(result is RemoteLyricsResult.ServiceFailure)
        result as RemoteLyricsResult.ServiceFailure
        assertEquals(503, result.statusCode)
        assertEquals("maintenance", result.message)
    }

    private fun source(transport: LyricsHttpTransport) = LrclibLyricsSource(
        transport = transport,
        baseUrl = "https://lrclib.test/api/",
        minimumRequestIntervalMs = 0L,
    )

    private class QueueTransport(vararg responses: LyricsHttpResponse) : LyricsHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse {
            urls += url
            this.headers += headers
            return responses.removeFirst()
        }
    }

    private companion object {
        val RECORD_JSON = """
            {
              "id":3396226,
              "name":"I Want to Live",
              "trackName":"I Want to Live",
              "artistName":"Borislav Slavov",
              "albumName":"Baldur's Gate 3",
              "duration":233.4,
              "instrumental":false,
              "plainLyrics":"I feel your breath",
              "syncedLyrics":"[00:17.12] I feel your breath",
              "lyricsfile":"version: '1.0'"
            }
        """.trimIndent()
    }
}
