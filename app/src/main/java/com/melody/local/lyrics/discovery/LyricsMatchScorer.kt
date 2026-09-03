package com.melody.local.lyrics.discovery

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object LyricsMatchScorer {
    const val LOCAL_AUTO_IMPORT_SCORE = 85
    const val ONLINE_AUTO_IMPORT_SCORE = 78

    fun rankLocal(
        track: LyricsTrack,
        candidates: List<LocalLyricsCandidate>,
    ): List<RankedLocalLyrics> {
        val ranked = candidates.mapNotNull { candidate ->
            scoreLocal(track, candidate)
        }
        return ranked.sortedWith(
            compareByDescending<RankedLocalLyrics> { it.score }
                .thenBy { it.reason.ordinal }
                .thenBy { it.candidate.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.candidate.location },
        )
    }

    fun rankOnline(
        track: LyricsTrack,
        records: List<LrclibLyricsRecord>,
    ): List<RankedOnlineLyrics> = records
        .map { record ->
            val score = scoreOnline(track, record)
            RankedOnlineLyrics(
                record = record,
                score = score,
                canAutoImport = !record.instrumental &&
                    record.preferredLyrics != null &&
                    score >= ONLINE_AUTO_IMPORT_SCORE,
            )
        }
        .sortedWith(
            compareByDescending<RankedOnlineLyrics> { it.score }
                .thenByDescending { it.record.isSynced }
                .thenBy { it.record.id },
        )

    fun scoreOnline(track: LyricsTrack, record: LrclibLyricsRecord): Int {
        val titleScore = similarity(track.title, record.trackName) * 55.0
        val artistScore = if (isUnknown(track.artist)) {
            0.0
        } else {
            similarity(track.artist, record.artistName) * 20.0
        }
        val albumScore = if (isUnknown(track.album)) {
            0.0
        } else {
            similarity(track.album, record.albumName) * 8.0
        }
        val durationScore = durationScore(track.durationMs, record.durationSeconds)
        val syncedBonus = if (record.isSynced) 5.0 else 0.0
        return (titleScore + artistScore + albumScore + durationScore + syncedBonus)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun scoreLocal(
        track: LyricsTrack,
        candidate: LocalLyricsCandidate,
    ): RankedLocalLyrics? {
        if (!candidate.displayName.substringAfterLast('.', "").equals("lrc", ignoreCase = true)) {
            return null
        }
        val candidateStem = candidate.displayName.substringBeforeLast('.')
        val audioStem = track.sourceFileName?.substringBeforeLast('.')?.trim().orEmpty()
        val title = track.title.trim()
        val artist = track.artist.trim()
        val titleArtist = listOf(title, artist).filter(String::isNotBlank).joinToString(" - ")
        val artistTitle = listOf(artist, title).filter(String::isNotBlank).joinToString(" - ")

        val (score, reason) = when {
            audioStem.isNotBlank() && candidateStem.equals(audioStem, ignoreCase = true) ->
                100 to LocalMatchReason.SAME_AUDIO_FILE_NAME
            audioStem.isNotBlank() && comparable(candidateStem) == comparable(audioStem) ->
                96 to LocalMatchReason.NORMALIZED_AUDIO_FILE_NAME
            title.isNotBlank() && candidateStem.equals(title, ignoreCase = true) ->
                92 to LocalMatchReason.SAME_TRACK_TITLE
            title.isNotBlank() && comparable(candidateStem) == comparable(title) ->
                88 to LocalMatchReason.NORMALIZED_TRACK_TITLE
            titleArtist.isNotBlank() && comparable(candidateStem) == comparable(titleArtist) ->
                86 to LocalMatchReason.TITLE_THEN_ARTIST
            artistTitle.isNotBlank() && comparable(candidateStem) == comparable(artistTitle) ->
                85 to LocalMatchReason.ARTIST_THEN_TITLE
            else -> {
                val similarity = similarity(candidateStem, title)
                if (similarity < 0.72) return null
                // Fuzzy hits remain visible to the user but are never imported silently.
                (60 + similarity * 23).roundToInt().coerceAtMost(83) to
                    LocalMatchReason.FUZZY_TITLE
            }
        }
        return RankedLocalLyrics(
            candidate = candidate,
            score = score,
            reason = reason,
            canAutoImport = score >= LOCAL_AUTO_IMPORT_SCORE &&
                reason != LocalMatchReason.FUZZY_TITLE,
        )
    }

    internal fun similarity(left: String, right: String): Double {
        val a = comparable(left)
        val b = comparable(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val tokenScore = jaccard(tokens(left), tokens(right))
        val bigramScore = diceBigrams(a, b)
        return maxOf(tokenScore, bigramScore)
    }

    internal fun comparable(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun tokens(value: String): Set<String> = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)
        .toSet()

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    private fun diceBigrams(left: String, right: String): Double {
        if (left.length == 1 || right.length == 1) return if (left == right) 1.0 else 0.0
        val leftCounts = left.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var overlap = 0
        right.windowed(2).forEach { bigram ->
            val count = leftCounts[bigram] ?: 0
            if (count > 0) {
                overlap++
                leftCounts[bigram] = count - 1
            }
        }
        return 2.0 * overlap / (left.length - 1 + right.length - 1)
    }

    private fun durationScore(trackDurationMs: Long, recordDurationSeconds: Double): Double {
        if (trackDurationMs <= 0L || recordDurationSeconds <= 0.0) return 0.0
        val difference = abs(trackDurationMs / 1_000.0 - recordDurationSeconds)
        return when {
            difference <= 2.0 -> 12.0
            difference <= 5.0 -> 8.0
            difference <= 10.0 -> 4.0
            else -> 0.0
        }
    }

    private fun isUnknown(value: String): Boolean {
        val normalized = comparable(value)
        return normalized.isBlank() || normalized in UNKNOWN_VALUES
    }

    private val UNKNOWN_VALUES = setOf(
        comparable("未知歌手"),
        comparable("未知专辑"),
        comparable("unknown artist"),
        comparable("unknown album"),
        comparable("<unknown>"),
    )
}
