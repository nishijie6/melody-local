package com.melody.local.lyrics.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchScorerTest {
    private val track = LyricsTrack(
        songId = 7L,
        title = "夜曲",
        artist = "周杰伦",
        album = "十一月的萧邦",
        durationMs = 226_000L,
        sourceFileName = "01 - 夜曲.FLAC",
    )

    @Test
    fun `same audio stem wins case-insensitively`() {
        val ranked = LyricsMatchScorer.rankLocal(
            track,
            listOf(
                candidate("周杰伦 - 夜曲.lrc"),
                candidate("01 - 夜曲.lRc"),
                candidate("夜曲.LRC"),
            ),
        )

        assertEquals("01 - 夜曲.lRc", ranked.first().candidate.displayName)
        assertEquals(100, ranked.first().score)
        assertEquals(LocalMatchReason.SAME_AUDIO_FILE_NAME, ranked.first().reason)
        assertTrue(ranked.first().canAutoImport)
    }

    @Test
    fun `actual display name wins when editable title metadata is unrelated`() {
        val ranked = LyricsMatchScorer.rankLocal(
            track.copy(title = "歌曲名", sourceFileName = "01.mp3"),
            listOf(candidate("01.lrc")),
        )

        assertEquals(100, ranked.single().score)
        assertEquals(LocalMatchReason.SAME_AUDIO_FILE_NAME, ranked.single().reason)
        assertTrue(ranked.single().canAutoImport)
    }

    @Test
    fun `title and artist forms have deterministic priority`() {
        val ranked = LyricsMatchScorer.rankLocal(
            track.copy(sourceFileName = null),
            listOf(
                candidate("周杰伦 - 夜曲.lrc"),
                candidate("夜曲 - 周杰伦.lrc"),
                candidate("夜曲.lrc"),
            ),
        )

        assertEquals(
            listOf("夜曲.lrc", "夜曲 - 周杰伦.lrc", "周杰伦 - 夜曲.lrc"),
            ranked.map { it.candidate.displayName },
        )
        assertEquals(listOf(92, 86, 85), ranked.map { it.score })
    }

    @Test
    fun `punctuation and full-width forms normalize but random files are rejected`() {
        val ranked = LyricsMatchScorer.rankLocal(
            track.copy(title = "ＡＢＣ Song", sourceFileName = null),
            listOf(
                candidate("abc-song.lrc"),
                candidate("unrelated.lrc"),
                candidate("cover.jpg"),
            ),
        )

        assertEquals(1, ranked.size)
        assertEquals(LocalMatchReason.NORMALIZED_TRACK_TITLE, ranked.single().reason)
        assertTrue(ranked.single().canAutoImport)
    }

    @Test
    fun `online score rewards metadata duration and synchronized text`() {
        val exact = record(
            id = 1L,
            trackName = "夜曲",
            artistName = "周杰伦",
            albumName = "十一月的萧邦",
            durationSeconds = 226.4,
            syncedLyrics = "[00:01.00]一群嗜血的蚂蚁",
        )
        val wrong = record(
            id = 2L,
            trackName = "夜曲 (Live)",
            artistName = "其他歌手",
            albumName = "演唱会",
            durationSeconds = 300.0,
            syncedLyrics = null,
        )

        val ranked = LyricsMatchScorer.rankOnline(track, listOf(wrong, exact))

        assertEquals(1L, ranked.first().record.id)
        assertEquals(100, ranked.first().score)
        assertTrue(ranked.first().canAutoImport)
        assertFalse(ranked.last().canAutoImport)
    }

    @Test
    fun `instrumental result is never auto-imported`() {
        val instrumental = record(
            id = 3L,
            trackName = track.title,
            artistName = track.artist,
            albumName = track.album,
            durationSeconds = 226.0,
            syncedLyrics = null,
            instrumental = true,
        )

        val match = LyricsMatchScorer.rankOnline(track, listOf(instrumental)).single()

        assertEquals(95, match.score)
        assertFalse(match.canAutoImport)
    }

    private fun candidate(name: String) = LocalLyricsCandidate(
        location = "file:///music/$name",
        displayName = name,
    )

    private fun record(
        id: Long,
        trackName: String,
        artistName: String,
        albumName: String,
        durationSeconds: Double,
        syncedLyrics: String?,
        instrumental: Boolean = false,
    ) = LrclibLyricsRecord(
        id = id,
        trackName = trackName,
        artistName = artistName,
        albumName = albumName,
        durationSeconds = durationSeconds,
        instrumental = instrumental,
        plainLyrics = if (instrumental) null else "plain",
        syncedLyrics = syncedLyrics,
    )
}
