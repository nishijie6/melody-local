package com.melody.local.lyrics

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.melody.local.data.Song
import com.melody.local.lyrics.discovery.LrclibLyricsSource
import com.melody.local.lyrics.discovery.LyricsSearchRequest
import com.melody.local.lyrics.discovery.LyricsTrack
import com.melody.local.lyrics.discovery.LocalLyricsLookup
import com.melody.local.lyrics.discovery.LocalLyricsSource
import com.melody.local.lyrics.discovery.MediaStoreSameDirectoryLyricsSource
import com.melody.local.lyrics.discovery.OnlineLyricsSource
import com.melody.local.lyrics.discovery.RankedOnlineLyrics
import com.melody.local.lyrics.discovery.RemoteLyricsResult
import com.melody.local.lyrics.discovery.toLyricsTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class LyricsOrigin {
    CACHED,
    AUTHORIZED_FOLDER,
    SAME_MEDIA_DIRECTORY,
    EMBEDDED_TAG,
    ONLINE,
}

fun interface AutomaticLyricsResolver {
    suspend fun resolveAutomatically(song: Song): LyricsResolution
}

interface LyricsResolverApi : AutomaticLyricsResolver {
    val preferences: LyricsAutomationPreferences
    suspend fun resolve(song: Song, allowOnline: Boolean): LyricsResolution
    suspend fun searchOnline(song: Song, keywords: String? = null): LyricsResolution
    suspend fun applyOnline(songId: Long, result: RankedOnlineLyrics): LyricsResolution
}

sealed interface LyricsResolution {
    data class Applied(
        val lyrics: ParsedLyrics,
        val origin: LyricsOrigin,
    ) : LyricsResolution

    data class OnlineChoices(val matches: List<RankedOnlineLyrics>) : LyricsResolution
    data object NoResults : LyricsResolution
    data class RateLimited(val retryAfterSeconds: Long) : LyricsResolution
    data class Failure(val message: String) : LyricsResolution
}

class LyricsResolver(
    context: Context,
    private val store: LyricsStore,
    override val preferences: LyricsAutomationPreferences = LyricsAutomationPreferences(context),
    private val embeddedExtractor: EmbeddedLyricsExtractor = AndroidEmbeddedLyricsExtractor(context),
    private val onlineSource: OnlineLyricsSource = LrclibLyricsSource(
        clientIdentifier = "Yinlan/1.4.0 (https://github.com/nishijie6/melody-local)",
    ),
    extraLocalSources: List<LocalLyricsSource> = emptyList(),
) : LyricsResolverApi {
    private val songUris = ConcurrentHashMap<Long, Uri>()
    private val resolutionLocks = ConcurrentHashMap<Long, Mutex>()
    private val localSources = buildList {
        add(MediaStoreSameDirectoryLyricsSource(context) { track -> songUris[track.songId] })
        add(
            AuthorizedFolderLyricsSource(
                scanner = AuthorizedLyricsFolderScanner(context),
                preferences = preferences,
            )
        )
        addAll(extraLocalSources)
    }

    /** Local sidecar → embedded tag → optional online match. */
    override suspend fun resolveAutomatically(song: Song): LyricsResolution {
        if (store.isAutomaticDiscoverySuppressed(song.id)) return LyricsResolution.NoResults
        return resolve(song, allowOnline = preferences.get().automaticOnlineLookup)
    }

    override suspend fun resolve(
        song: Song,
        allowOnline: Boolean,
    ): LyricsResolution = resolutionLocks.getOrPut(song.id) { Mutex() }.withLock {
        // The playback service and foreground UI can observe the same transition. Rechecking under
        // one process-wide resolver lock avoids duplicate directory scans and online requests.
        store.load(song.id)?.let { return@withLock LyricsResolution.Applied(it, LyricsOrigin.CACHED) }
        songUris[song.id] = song.contentUri
        val track = song.toLyricsTrack()
        for ((index, source) in localSources.withIndex()) {
            when (val lookup = source.find(track)) {
                is LocalLyricsLookup.Found -> if (lookup.best.canAutoImport) {
                    val imported = failureAsNull {
                        store.importIfAbsent(song.id, lookup.best.candidate.location.toUri())
                    }
                    if (imported != null) {
                        return@withLock LyricsResolution.Applied(
                            imported,
                            if (index == 0) LyricsOrigin.SAME_MEDIA_DIRECTORY
                            else LyricsOrigin.AUTHORIZED_FOLDER,
                        )
                    }
                    store.load(song.id)?.let {
                        return@withLock LyricsResolution.Applied(it, LyricsOrigin.CACHED)
                    }
                }
                else -> Unit
            }
        }

        if (store.isAutomaticDiscoverySuppressed(song.id)) {
            return@withLock LyricsResolution.NoResults
        }

        if (preferences.get().readEmbeddedLyrics) {
            val embedded = failureAsNull { embeddedExtractor.extract(song.contentUri) }
            if (embedded != null) {
                val parsed = failureAsNull { store.saveIfAbsent(song.id, embedded.text) }
                if (parsed != null) {
                    return@withLock LyricsResolution.Applied(parsed, LyricsOrigin.EMBEDDED_TAG)
                }
                store.load(song.id)?.let {
                    return@withLock LyricsResolution.Applied(it, LyricsOrigin.CACHED)
                }
            }
        }

        if (store.isAutomaticDiscoverySuppressed(song.id)) {
            return@withLock LyricsResolution.NoResults
        }
        if (!allowOnline) return@withLock LyricsResolution.NoResults
        searchAndMaybeApply(song.id, track, keywords = null, autoApply = true)
    }

    override suspend fun searchOnline(song: Song, keywords: String?): LyricsResolution {
        songUris[song.id] = song.contentUri
        return searchAndMaybeApply(
            songId = song.id,
            track = song.toLyricsTrack(),
            keywords = keywords,
            autoApply = false,
        )
    }

    override suspend fun applyOnline(songId: Long, result: RankedOnlineLyrics): LyricsResolution {
        return resolutionLocks.getOrPut(songId) { Mutex() }.withLock {
            applyOnlineLocked(songId, result, replaceExisting = true)
        }
    }

    private suspend fun applyOnlineLocked(
        songId: Long,
        result: RankedOnlineLyrics,
        replaceExisting: Boolean,
    ): LyricsResolution {
        val recordResult = onlineSource.download(result.record.id)
        return when (recordResult) {
            is RemoteLyricsResult.Success -> {
                val text = recordResult.value.preferredLyrics
                    ?: return LyricsResolution.Failure("在线结果没有可显示的歌词")
                val saved = try {
                    if (replaceExisting) store.save(songId, text) else store.saveIfAbsent(songId, text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return LyricsResolution.Failure(error.message ?: "在线歌词保存失败")
                }
                if (saved != null) {
                    LyricsResolution.Applied(saved, LyricsOrigin.ONLINE)
                } else {
                    store.load(songId)
                        ?.let { LyricsResolution.Applied(it, LyricsOrigin.CACHED) }
                        ?: LyricsResolution.Failure("已有歌词文件，自动匹配未覆盖它")
                }
            }
            RemoteLyricsResult.NoResults -> LyricsResolution.NoResults
            is RemoteLyricsResult.NetworkFailure -> LyricsResolution.Failure(recordResult.message)
            is RemoteLyricsResult.RateLimited ->
                LyricsResolution.RateLimited(recordResult.retryAfterSeconds)
            is RemoteLyricsResult.ServiceFailure -> LyricsResolution.Failure(recordResult.message)
        }
    }

    private suspend fun searchAndMaybeApply(
        songId: Long,
        track: LyricsTrack,
        keywords: String?,
        autoApply: Boolean,
    ): LyricsResolution = when (
        val result = onlineSource.search(LyricsSearchRequest(track = track, keywords = keywords))
    ) {
        is RemoteLyricsResult.Success -> {
            val matches = result.value
            val recommended = matches.firstOrNull { it.canAutoImport }
            if (autoApply && recommended != null) {
                applyOnlineLocked(songId, recommended, replaceExisting = false)
            } else if (matches.isNotEmpty()) {
                LyricsResolution.OnlineChoices(matches)
            } else {
                LyricsResolution.NoResults
            }
        }
        RemoteLyricsResult.NoResults -> LyricsResolution.NoResults
        is RemoteLyricsResult.NetworkFailure -> LyricsResolution.Failure(result.message)
        is RemoteLyricsResult.RateLimited -> LyricsResolution.RateLimited(result.retryAfterSeconds)
        is RemoteLyricsResult.ServiceFailure -> LyricsResolution.Failure(result.message)
    }

    private suspend fun <T> failureAsNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
