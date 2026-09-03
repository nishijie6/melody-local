package com.melody.local.systemlyrics

import android.content.Context
import android.content.SharedPreferences
import android.os.FileObserver
import androidx.media3.common.C
import androidx.media3.common.Player
import com.melody.local.data.Song
import com.melody.local.lyrics.AppLyricsRuntime
import com.melody.local.lyrics.ParsedLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service-side source of truth for system lyrics. It loads the current song's private LRC copy,
 * applies route-aware timing and only publishes when the visible line or playback state changes.
 */
class SystemLyricsCoordinator(
    context: Context,
    private val player: Player,
    private val onSnapshotChanged: (SystemLyricSnapshot) -> Unit,
) : Player.Listener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lyricsRuntime = AppLyricsRuntime.get(appContext)
    private val lyricsRepository = lyricsRuntime.repository
    private val lyricsLoader = ServiceLyricsLoader(lyricsRepository, lyricsRuntime.resolver)
    private val settings = SystemLyricsSettings(appContext)
    private val lyricsDirectory = File(appContext.filesDir, "lyrics").apply { mkdirs() }
    @Suppress("DEPRECATION")
    private val lyricFileObserver = object : FileObserver(
        lyricsDirectory.absolutePath,
        FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.DELETE or FileObserver.DELETE_SELF,
    ) {
        override fun onEvent(event: Int, path: String?) {
            val songId = loadedSongId ?: return
            if (path != "$songId.lrc" && event and FileObserver.DELETE_SELF == 0) return
            scope.launch { reloadSong(songId) }
        }
    }
    private val outputMonitor = AudioOutputMonitor(appContext) { route ->
        outputRoute = route
        render(force = true)
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch { render(force = true) }
    }
    private val automationSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        automationReloadJob?.cancel()
        automationReloadJob = scope.launch {
            // One preference update writes several keys; coalesce their callbacks into one retry.
            delay(100L)
            if (loadedLyrics == null && loadedSongId != null) {
                loadJob?.cancel()
                loadJob = null
                loadedSongResolved = false
                ensureSongLoaded()
            }
        }
    }

    private var outputRoute = AudioOutputRoute.UNKNOWN
    private var loadedSongId: Long? = null
    private var loadedLyrics: ParsedLyrics? = null
    private var loadedSongResolved = true
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var automationReloadJob: Job? = null
    private var lastSnapshot = SystemLyricSnapshot()
    private var contentRevision = 0L

    fun start() {
        player.addListener(this)
        settings.registerListener(settingsListener)
        lyricsRuntime.resolver.preferences.registerListener(automationSettingsListener)
        lyricFileObserver.startWatching()
        outputMonitor.start()
        ensureSongLoaded()
        updateTicker()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        ensureSongLoaded()
        render()
        updateTicker()
    }

    fun close() {
        player.removeListener(this)
        settings.unregisterListener(settingsListener)
        lyricsRuntime.resolver.preferences.unregisterListener(automationSettingsListener)
        lyricFileObserver.stopWatching()
        outputMonitor.stop()
        loadJob?.cancel()
        tickerJob?.cancel()
        automationReloadJob?.cancel()
        val cleared = SystemLyricSnapshot(
            outputRoute = outputRoute,
            contentRevision = contentRevision,
        )
        SystemLyricsRuntime.publish(cleared)
        onSnapshotChanged(cleared)
        scope.cancel()
    }

    private fun ensureSongLoaded() {
        val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (songId == loadedSongId && (loadedSongResolved || loadJob?.isActive == true)) return
        loadedSongId = songId
        loadedLyrics = null
        loadedSongResolved = songId == null
        loadJob?.cancel()
        render(force = true)
        if (songId == null) return
        val song = currentSong(songId)
        loadJob = scope.launch {
            val lyrics = try {
                if (song == null) lyricsRepository.load(songId) else lyricsLoader.load(song)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (loadedSongId != songId) return@launch
            loadedLyrics = lyrics
            loadedSongResolved = true
            contentRevision++
            render(force = true)
        }
    }

    private fun reloadSong(songId: Long) {
        if (loadedSongId != songId) return
        loadedSongResolved = false
        ensureSongLoaded()
    }

    private fun updateTicker() {
        if (!player.isPlaying) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                render()
                delay(200L)
            }
        }
    }

    private fun render(force: Boolean = false) {
        val songId = loadedSongId
        val lyrics = loadedLyrics
        val appliedDelay = settings.appliedDelayMs(outputRoute)
        val position = LyricsTimingPolicy.lyricPositionMs(
            playbackPositionMs = player.currentPosition.coerceAtLeast(0L),
            appliedDelayMs = appliedDelay,
        )
        val (currentLine, nextLine) = SystemLyricsLineSelector.visibleLines(lyrics, position)
        val snapshot = SystemLyricSnapshot(
            songId = songId,
            currentLine = currentLine,
            nextLine = nextLine,
            isPlaying = player.isPlaying,
            outputRoute = outputRoute,
            appliedDelayMs = appliedDelay,
            contentRevision = contentRevision,
        )
        if (!force && snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        SystemLyricsRuntime.publish(snapshot)
        onSnapshotChanged(snapshot)
    }

    private fun currentSong(expectedSongId: Long): Song? {
        val item = player.currentMediaItem ?: return null
        if (item.mediaId.toLongOrNull() != expectedSongId) return null
        val uri = item.localConfiguration?.uri ?: return null
        val metadata = item.mediaMetadata
        val safeDuration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L
        return Song(
            id = expectedSongId,
            title = metadata.title?.toString()?.takeIf(String::isNotBlank) ?: item.mediaId,
            artist = metadata.artist?.toString()?.takeIf(String::isNotBlank) ?: "未知歌手",
            album = metadata.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: "未知专辑",
            albumId = 0L,
            durationMs = safeDuration,
            trackNumber = 0,
            dateAddedSeconds = 0L,
            contentUri = uri,
            albumArtUri = metadata.artworkUri,
        )
    }

}

internal object SystemLyricsLineSelector {
    fun visibleLines(lyrics: ParsedLyrics?, positionMs: Long): Pair<String, String> {
        val lines = lyrics?.structuredLines.orEmpty()
        if (lines.isEmpty()) return "" to ""
        if (lyrics?.isSynced != true) {
            return lines.first().systemDisplayText() to
                lines.getOrNull(1)?.systemDisplayText().orEmpty()
        }
        val firstTime = lines.first().timeMs ?: 0L
        if (positionMs < firstTime) return "" to lines.first().systemDisplayText()
        val currentIndex = lyrics.activeStructuredLineIndex(positionMs).coerceIn(lines.indices)
        return lines[currentIndex].systemDisplayText() to
            lines.getOrNull(currentIndex + 1)?.systemDisplayText().orEmpty()
    }

    private fun com.melody.local.lyrics.StructuredLyricLine.systemDisplayText(): String {
        val primary = original?.text ?: layers.firstOrNull()?.text.orEmpty()
        val secondary = translation?.text ?: romanization?.text
        return listOfNotNull(primary.takeIf(String::isNotBlank), secondary?.takeIf(String::isNotBlank))
            .joinToString("\n")
    }
}
