package com.melody.local.lyrics.discovery

/** Metadata used for local and remote lyric matching. */
data class LyricsTrack(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourceFileName: String? = null,
)

data class LocalLyricsCandidate(
    /** A stable URI or path that the integration layer can open. */
    val location: String,
    val displayName: String,
    val sizeBytes: Long? = null,
)

enum class LocalMatchReason {
    SAME_AUDIO_FILE_NAME,
    NORMALIZED_AUDIO_FILE_NAME,
    SAME_TRACK_TITLE,
    NORMALIZED_TRACK_TITLE,
    TITLE_THEN_ARTIST,
    ARTIST_THEN_TITLE,
    FUZZY_TITLE,
}

data class RankedLocalLyrics(
    val candidate: LocalLyricsCandidate,
    val score: Int,
    val reason: LocalMatchReason,
    val canAutoImport: Boolean,
)

sealed interface LocalLyricsLookup {
    data class Found(
        val best: RankedLocalLyrics,
        val alternatives: List<RankedLocalLyrics>,
    ) : LocalLyricsLookup

    data class NoMatch(val candidateCount: Int = 0) : LocalLyricsLookup

    /** The source cannot inspect the song directory, for example because storage access is absent. */
    data class Unavailable(val reason: String) : LocalLyricsLookup

    data class Failure(val message: String, val cause: Throwable? = null) : LocalLyricsLookup
}

fun interface LocalLyricsSource {
    suspend fun find(track: LyricsTrack): LocalLyricsLookup
}

data class LrclibLyricsRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
) {
    val preferredLyrics: String?
        get() = syncedLyrics?.takeIf(String::isNotBlank)
            ?: plainLyrics?.takeIf(String::isNotBlank)

    val isSynced: Boolean
        get() = !syncedLyrics.isNullOrBlank()
}

data class RankedOnlineLyrics(
    val record: LrclibLyricsRecord,
    val score: Int,
    val canAutoImport: Boolean,
)

data class LyricsSearchRequest(
    val track: LyricsTrack,
    /** When set, LRCLIB's broad `q` search is used instead of structured fields. */
    val keywords: String? = null,
)

sealed interface RemoteLyricsResult<out T> {
    data class Success<T>(val value: T) : RemoteLyricsResult<T>

    data object NoResults : RemoteLyricsResult<Nothing>

    data class NetworkFailure(
        val message: String,
        val cause: Throwable? = null,
    ) : RemoteLyricsResult<Nothing>

    data class RateLimited(val retryAfterSeconds: Long) : RemoteLyricsResult<Nothing>

    data class ServiceFailure(
        val statusCode: Int,
        val message: String,
    ) : RemoteLyricsResult<Nothing>
}

interface OnlineLyricsSource {
    suspend fun search(request: LyricsSearchRequest): RemoteLyricsResult<List<RankedOnlineLyrics>>
    suspend fun download(recordId: Long): RemoteLyricsResult<LrclibLyricsRecord>
}

sealed interface LyricsDiscoveryResult {
    data class LocalMatch(val match: RankedLocalLyrics) : LyricsDiscoveryResult

    data class OnlineMatches(
        val matches: List<RankedOnlineLyrics>,
        val recommended: RankedOnlineLyrics?,
    ) : LyricsDiscoveryResult

    data class NoResults(val localCandidateCount: Int = 0) : LyricsDiscoveryResult

    data class NetworkFailure(val message: String) : LyricsDiscoveryResult

    data class RateLimited(val retryAfterSeconds: Long) : LyricsDiscoveryResult

    data class ServiceFailure(val statusCode: Int, val message: String) : LyricsDiscoveryResult
}

/**
 * Tries every same-directory source first. The network is touched only when no local candidate is
 * safe to auto-import. This preserves user-provided local lyrics and also makes offline playback
 * deterministic.
 */
class LyricsDiscoveryCoordinator(
    private val localSources: List<LocalLyricsSource>,
    private val onlineSource: OnlineLyricsSource,
) {
    suspend fun discover(
        track: LyricsTrack,
        allowOnline: Boolean = true,
    ): LyricsDiscoveryResult {
        var localCandidateCount = 0
        for (source in localSources) {
            when (val local = source.find(track)) {
                is LocalLyricsLookup.Found -> {
                    localCandidateCount += 1 + local.alternatives.size
                    if (local.best.canAutoImport) {
                        return LyricsDiscoveryResult.LocalMatch(local.best)
                    }
                }
                is LocalLyricsLookup.NoMatch -> localCandidateCount += local.candidateCount
                is LocalLyricsLookup.Unavailable,
                is LocalLyricsLookup.Failure,
                -> Unit
            }
        }

        if (!allowOnline) return LyricsDiscoveryResult.NoResults(localCandidateCount)
        return when (val remote = onlineSource.search(LyricsSearchRequest(track))) {
            is RemoteLyricsResult.Success -> {
                val matches = remote.value
                if (matches.isEmpty()) {
                    LyricsDiscoveryResult.NoResults(localCandidateCount)
                } else {
                    LyricsDiscoveryResult.OnlineMatches(
                        matches = matches,
                        recommended = matches.firstOrNull { it.canAutoImport },
                    )
                }
            }
            RemoteLyricsResult.NoResults -> LyricsDiscoveryResult.NoResults(localCandidateCount)
            is RemoteLyricsResult.NetworkFailure -> LyricsDiscoveryResult.NetworkFailure(remote.message)
            is RemoteLyricsResult.RateLimited -> LyricsDiscoveryResult.RateLimited(remote.retryAfterSeconds)
            is RemoteLyricsResult.ServiceFailure -> LyricsDiscoveryResult.ServiceFailure(
                statusCode = remote.statusCode,
                message = remote.message,
            )
        }
    }
}
