package com.melody.local.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.DuplicatePlaylistNameException
import com.melody.local.data.MusicLibrary
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.Song
import com.melody.local.lyrics.LyricLine
import com.melody.local.lyrics.LyricsAutomationPreferences
import com.melody.local.lyrics.LyricsResolverApi
import com.melody.local.lyrics.LyricsResolution
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import com.melody.local.lyrics.discovery.LrclibLyricsRecord
import com.melody.local.lyrics.discovery.RankedOnlineLyrics
import com.melody.local.media.MediaAuthorizationRequest
import com.melody.local.media.MediaOperationState
import com.melody.local.media.MediaOperationSummary
import com.melody.local.media.PlaylistMovePreview
import com.melody.local.media.RelocationStep
import com.melody.local.media.SongRelocationCoordinator
import com.melody.local.media.VideoAudioExtractor
import com.melody.local.media.VideoImportRequest
import com.melody.local.playback.PlaybackController
import com.melody.local.playback.PlaybackMode
import com.melody.local.playback.PlaybackUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun refreshPublishesSongsRejectsConcurrentLoadsAndSurfacesFailures() = runBlocking {
        val pendingSongs = CompletableDeferred<List<Song>>()
        val music = FakeMusicLibrary { pendingSongs.await() }
        val fixture = Fixture(music = music)

        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.isLoading.value }
        fixture.viewModel.refreshSongs()
        assertEquals(1, music.loadCount)

        pendingSongs.complete(listOf(song(1), song(2)))
        waitUntil { !fixture.viewModel.isLoading.value }
        assertEquals(listOf(1L, 2L), fixture.viewModel.allSongs.value.map { it.id })
        assertEquals(null, fixture.viewModel.libraryError.value)

        music.loader = { throw SecurityException("permission revoked") }
        fixture.viewModel.refreshSongs()
        waitUntil { !fixture.viewModel.isLoading.value && fixture.viewModel.libraryError.value != null }
        assertEquals("读取本地音乐失败，请检查音乐权限", fixture.viewModel.libraryError.value)
        assertEquals(listOf(1L, 2L), fixture.viewModel.allSongs.value.map { it.id })
        fixture.clear()
    }

    @Test
    fun selectedPlaylistPreservesStoredOrderDropsMissingSongsAndClears() = runBlocking {
        val fixture = Fixture(music = FakeMusicLibrary { listOf(song(1), song(2), song(3)) })
        val collection = launch { fixture.viewModel.selectedPlaylistSongs.collect() }
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 3 }

        fixture.playlists.songIds.value = listOf(3L, 999L, 1L)
        fixture.viewModel.selectPlaylist(42L)
        waitUntil { fixture.viewModel.selectedPlaylistSongs.value.map { it.id } == listOf(3L, 1L) }

        fixture.viewModel.selectPlaylist(null)
        waitUntil { fixture.viewModel.selectedPlaylistSongs.value.isEmpty() }
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun playlistCommandsEmitSuccessIdempotencyErrorsAndClearADeletedSelection() = runBlocking {
        val fixture = Fixture()
        val messages = mutableListOf<String>()
        val collection = launch { fixture.viewModel.messages.collect(messages::add) }
        delay(20)

        fixture.viewModel.createPlaylist("Roadtrip")
        waitUntil { messages.size == 1 }
        fixture.viewModel.createPlaylist("Favorites", songIdToAdd = 7L)
        waitUntil { messages.size == 2 }
        fixture.viewModel.renamePlaylist(1L, "Renamed")
        waitUntil { messages.size == 3 }
        fixture.viewModel.addSongToPlaylist(1L, 7L)
        waitUntil { messages.size == 4 }
        fixture.playlists.addResult = false
        fixture.viewModel.addSongToPlaylist(1L, 7L)
        waitUntil { messages.size == 5 }
        fixture.viewModel.removeSongFromPlaylist(1L, 7L)
        waitUntil { messages.size >= 6 }
        assertEquals(
            listOf("歌单已创建", "歌单已创建，歌曲已加入", "歌单已重命名", "已加入歌单", "歌曲已在歌单中", "已从歌单移除"),
            messages.take(6),
        )

        fixture.playlists.nameFailure = DuplicatePlaylistNameException("Roadtrip")
        fixture.viewModel.createPlaylist("Roadtrip")
        waitUntil { messages.last().contains("已存在") }

        fixture.viewModel.selectPlaylist(1L)
        fixture.viewModel.deletePlaylist(1L)
        waitUntil { messages.last() == "歌单已删除" }
        assertEquals(1L, fixture.playlists.deletedId)
        assertFalse(fixture.playlists.observedPlaylistIds.contains(null))
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun lyricsStateActionsAndViewModelCleanupUseInjectedBoundaries() = runBlocking {
        val parsed = ParsedLyrics(listOf(LyricLine(1_000L, "line")), isSynced = true)
        val fixture = Fixture()
        val states = mutableListOf<LyricsUiState>()
        val messages = mutableListOf<String>()
        val stateCollection = launch { fixture.viewModel.lyrics.collect(states::add) }
        val messageCollection = launch { fixture.viewModel.messages.collect(messages::add) }
        delay(20)
        assertEquals(LyricsUiState.NoSong, fixture.viewModel.lyrics.value)

        val allowFirstLyricLoad = CompletableDeferred<Unit>()
        fixture.lyrics.loadGate = allowFirstLyricLoad
        fixture.player.mutableState.value = PlaybackUiState(mediaId = 1L)
        waitUntil { fixture.viewModel.lyrics.value == LyricsUiState.Loading }
        allowFirstLyricLoad.complete(Unit)
        fixture.lyrics.loadGate = null
        waitUntil { fixture.viewModel.lyrics.value == LyricsUiState.Missing }
        assertTrue(states.any { it == LyricsUiState.Loading })

        fixture.lyrics.loaded[1L] = Result.success<ParsedLyrics?>(parsed)
        fixture.player.mutableState.value = PlaybackUiState(
            mediaId = 1L,
            lyricsContentRevision = 1L,
        )
        waitUntil { fixture.viewModel.lyrics.value is LyricsUiState.Ready }

        fixture.lyrics.importResult = Result.success(parsed)
        fixture.viewModel.importLyrics(1L, Uri.parse("content://lyrics/1"))
        waitUntil { fixture.viewModel.lyrics.value is LyricsUiState.Ready }
        waitUntil { messages.lastOrNull() == "歌词已导入" }

        fixture.lyrics.loaded[2L] = Result.failure(IllegalStateException("corrupt lyric"))
        fixture.player.mutableState.value = PlaybackUiState(mediaId = 2L)
        waitUntil { fixture.viewModel.lyrics.value is LyricsUiState.Error }
        assertEquals("corrupt lyric", (fixture.viewModel.lyrics.value as LyricsUiState.Error).message)

        fixture.viewModel.deleteCurrentLyrics()
        waitUntil { messages.last() == "歌词已移除" }
        assertEquals(2L, fixture.lyrics.deletedSongId)

        stateCollection.cancel()
        messageCollection.cancel()
        fixture.clear()
        assertEquals(1, fixture.player.releaseCount)
    }

    @Test
    fun blankPlaylistNamesAndLyricsFailuresStayInsideTheViewModelBoundary() = runBlocking {
        val fixture = Fixture()
        val messages = mutableListOf<String>()
        val collection = launch { fixture.viewModel.messages.collect(messages::add) }
        delay(20)

        fixture.viewModel.createPlaylist("   ")
        fixture.viewModel.renamePlaylist(1L, "  ")
        delay(50)
        assertEquals(0, fixture.playlists.createCalls)
        assertEquals(0, fixture.playlists.renameCalls)

        fixture.viewModel.deleteCurrentLyrics()
        delay(50)
        assertEquals(null, fixture.lyrics.deletedSongId)

        fixture.lyrics.importResult = Result.failure(IllegalStateException("invalid lyric"))
        fixture.viewModel.importLyrics(1L, Uri.parse("content://lyrics/failure"))
        waitUntil { messages.lastOrNull() == "invalid lyric" }

        collection.cancel()
        fixture.clear()
    }

    @Test
    fun switchingSongsCancelsStaleOnlineResults() = runBlocking {
        val resolver = FakeLyricsResolver(application)
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(song(1), song(2)) },
            lyricsResolver = resolver,
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 2 }

        fixture.player.mutableState.value = PlaybackUiState(mediaId = 1L)
        fixture.viewModel.searchOnlineLyrics()
        resolver.firstSearchStarted.await()

        fixture.player.mutableState.value = PlaybackUiState(mediaId = 2L)
        waitUntil { fixture.viewModel.lyricsSearchState.value == LyricsSearchUiState.Idle }
        fixture.viewModel.searchOnlineLyrics()
        waitUntil {
            (fixture.viewModel.lyricsSearchState.value as? LyricsSearchUiState.Results)?.songId == 2L
        }

        resolver.releaseFirstSearch.complete(Unit)
        delay(100)
        assertEquals(
            2L,
            (fixture.viewModel.lyricsSearchState.value as LyricsSearchUiState.Results).songId,
        )
        fixture.clear()
    }

    @Test
    fun switchingSongsDoesNotReopenAStaleLyricsEditor() = runBlocking {
        val fixture = Fixture()
        val allowRead = CompletableDeferred<Unit>()
        fixture.lyrics.readGate = allowRead
        fixture.lyrics.rawText = "[00:01.00]old song"
        fixture.player.mutableState.value = PlaybackUiState(mediaId = 1L)

        fixture.viewModel.openLyricsEditor()
        fixture.player.mutableState.value = PlaybackUiState(mediaId = 2L)
        allowRead.complete(Unit)
        delay(100)

        assertEquals(null, fixture.viewModel.lyricsEditorDraft.value)
        fixture.clear()
    }

    @Test
    fun videoImportUsesEditedMetadataPublishesProgressAndRejectsDuplicateWork() = runBlocking {
        val extractor = FakeVideoAudioExtractor()
        val fixture = Fixture(videoExtractor = extractor)
        val source = Uri.fromFile(application.cacheDir.resolve("picked-video.mp4"))

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("Edited title", "Edited artist", "Edited album", true)
        waitUntil { extractor.requests.size == 1 }
        assertEquals("Edited title", extractor.requests.single().title)
        assertEquals("Edited artist", extractor.requests.single().artist)
        assertTrue(extractor.requests.single().extractArtwork)
        waitUntil { fixture.viewModel.videoImportState.value is MediaOperationState.Processing }

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("Duplicate", "Artist", "Album", false)
        delay(100)
        assertEquals(1, extractor.requests.size)

        extractor.state.value = MediaOperationState.Completed(
            MediaOperationSummary(imported = 1, songId = 5L)
        )
        waitUntil { fixture.viewModel.videoImportState.value is MediaOperationState.Completed }
        fixture.clear()
    }

    @Test
    fun rapidVideoImportDoubleTapOnlyStartsOneEnqueue() = runBlocking {
        val extractor = FakeVideoAudioExtractor().apply {
            enqueueGate = CompletableDeferred()
        }
        val fixture = Fixture(videoExtractor = extractor)
        val source = Uri.fromFile(application.cacheDir.resolve("double-tap-video.mp4"))

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("First", "Artist", "Album", false)
        assertTrue(fixture.viewModel.videoImportState.value is MediaOperationState.Preparing)
        fixture.viewModel.importVideoAudio("Second", "Artist", "Album", false)

        waitUntil { extractor.enqueueCalls == 1 }
        delay(100L)
        assertEquals(1, extractor.enqueueCalls)
        extractor.enqueueGate?.complete(Unit)
        waitUntil { extractor.requests.size == 1 }
        assertEquals("First", extractor.requests.single().title)
        fixture.clear()
    }

    @Test
    fun rejectedVideoEnqueueRestoresPreviousUiStateAndKeepsDraft() = runBlocking {
        val extractor = FakeVideoAudioExtractor().apply {
            enqueueResult = false
            enqueueGate = CompletableDeferred()
        }
        val fixture = Fixture(videoExtractor = extractor)
        val source = Uri.fromFile(application.cacheDir.resolve("rejected-video.mp4"))

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("Rejected", "Artist", "Album", true)
        assertTrue(fixture.viewModel.videoImportState.value is MediaOperationState.Preparing)

        waitUntil { extractor.enqueueCalls == 1 }
        extractor.enqueueGate?.complete(Unit)
        waitUntil { fixture.viewModel.videoImportState.value is MediaOperationState.Idle }
        assertTrue(fixture.viewModel.videoImportDraft.value != null)
        fixture.clear()
    }

    @Test
    fun rejectedVideoEnqueueReattachesToRecoveredBackgroundWork() = runBlocking {
        val recovered = MediaOperationState.Processing("Recovered audio", 0, 1, 35)
        val extractor = FakeVideoAudioExtractor().apply {
            enqueueResult = false
            stateOnRejectedEnqueue = recovered
            enqueueGate = CompletableDeferred()
        }
        val fixture = Fixture(videoExtractor = extractor)
        val source = Uri.fromFile(application.cacheDir.resolve("recovered-video.mp4"))

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("Recovered", "Artist", "Album", true)
        waitUntil { extractor.enqueueCalls == 1 }
        extractor.enqueueGate?.complete(Unit)

        waitUntil { fixture.viewModel.videoImportState.value == recovered }
        assertTrue(fixture.viewModel.videoImportDraft.value != null)
        fixture.clear()
    }

    @Test
    fun videoCancellationWaitsForDurablePublicationReconciliation() = runBlocking {
        val reconciling = MediaOperationState.Processing(
            currentFile = "正在完成已写入音轨",
            completed = 0,
            total = 1,
            progressPercent = 99,
        )
        val extractor = FakeVideoAudioExtractor().apply {
            stateAfterCancel = reconciling
        }
        val fixture = Fixture(videoExtractor = extractor)
        val source = Uri.fromFile(application.cacheDir.resolve("cancel-during-publish.mp4"))

        fixture.viewModel.prepareVideoImport(source)
        waitUntil { fixture.viewModel.videoImportDraft.value != null }
        fixture.viewModel.importVideoAudio("Publishing", "Artist", "Album", false)
        waitUntil { fixture.viewModel.videoImportState.value is MediaOperationState.Processing }

        fixture.viewModel.cancelVideoImport()
        waitUntil { fixture.viewModel.videoImportState.value == reconciling }
        assertFalse(fixture.viewModel.videoImportState.value is MediaOperationState.Cancelled)

        extractor.state.value = MediaOperationState.Completed(
            MediaOperationSummary(imported = 1, songId = 71L)
        )
        waitUntil { fixture.viewModel.videoImportState.value is MediaOperationState.Completed }
        fixture.clear()
    }

    @Test
    fun relocationStopsPlaybackPreventsSameFrameDoubleStartAndKeepsPartialResultAfterDenial() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application)
        val fixture = Fixture(relocation = coordinator)
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value?.songCount == 3 }

        fixture.viewModel.startPlaylistMove("歌单汇总")
        assertTrue(fixture.viewModel.playlistMoveState.value is MediaOperationState.Preparing)
        fixture.viewModel.startPlaylistMove("歌单汇总")
        waitUntil { coordinator.startCalls == 1 }
        assertEquals(1, coordinator.startCalls)
        assertEquals(1, fixture.player.stopCount)

        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }
        assertTrue(fixture.viewModel.playlistMoveState.value is MediaOperationState.AwaitingSystemAuthorization)
        fixture.viewModel.resumePlaylistMove(false)
        waitUntil { fixture.viewModel.playlistMoveState.value is MediaOperationState.Completed }
        assertEquals(false, coordinator.lastAuthorizationGranted)
        val summary = (fixture.viewModel.playlistMoveState.value as MediaOperationState.Completed).summary
        assertEquals(2, summary.moved)
        assertEquals(1, summary.cancelled)
        fixture.clear()
    }

    private inner class Fixture(
        music: FakeMusicLibrary = FakeMusicLibrary { emptyList() },
        videoExtractor: VideoAudioExtractor? = null,
        relocation: SongRelocationCoordinator? = null,
        lyricsResolver: LyricsResolverApi? = null,
    ) {
        val playlists = FakePlaylistStore()
        val lyrics = FakeLyricsStore()
        val player = FakePlaybackController()
        private val store = ViewModelStore()
        private val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val viewModel: MainViewModel = ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
                    application,
                    music,
                    playlists,
                    lyrics,
                    player,
                    videoExtractor,
                    relocation,
                    lyricsResolver,
                ) as T
            },
        )[MainViewModel::class.java]

        fun clear() = store.clear()
    }

    private class FakeMusicLibrary(var loader: suspend () -> List<Song>) : MusicLibrary {
        var loadCount = 0
        override suspend fun loadSongs(): List<Song> {
            loadCount++
            return loader()
        }
    }

    private class FakePlaylistStore : PlaylistStore {
        override val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
        val songIds = MutableStateFlow<List<Long>>(emptyList())
        val observedPlaylistIds = mutableListOf<Long?>()
        var addResult = true
        var nameFailure: RuntimeException? = null
        var deletedId: Long? = null
        var createCalls = 0
        var renameCalls = 0

        override suspend fun create(name: String): Long {
            createCalls++
            nameFailure?.let { throw it }
            return if (name == "Favorites") 2L else 1L
        }

        override suspend fun rename(playlistId: Long, name: String) {
            renameCalls++
            nameFailure?.let { throw it }
        }

        override suspend fun delete(playlistId: Long) {
            deletedId = playlistId
        }

        override suspend fun addSong(playlistId: Long, songId: Long): Boolean = addResult
        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit
        override suspend fun getAllSongIds(): List<Long> = songIds.value.distinct()
        override suspend fun remapSongIds(remaps: Map<Long, Long>) {
            songIds.value = songIds.value.map { remaps[it] ?: it }
        }
        override fun observeSongIds(playlistId: Long): Flow<List<Long>> {
            observedPlaylistIds += playlistId
            return songIds
        }
    }

    private class FakeLyricsStore : LyricsStore {
        val loaded = mutableMapOf<Long, Result<ParsedLyrics?>>()
        var importResult: Result<ParsedLyrics> = Result.failure(IllegalStateException("not configured"))
        var deletedSongId: Long? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var rawText: String? = null

        override suspend fun load(songId: Long): ParsedLyrics? {
            loadGate?.await()
            return loaded[songId]?.getOrThrow()
        }
        override suspend fun import(songId: Long, uri: Uri): ParsedLyrics = importResult.getOrThrow()
        override suspend fun readRaw(songId: Long): String? {
            readGate?.await()
            return rawText
        }
        override suspend fun delete(songId: Long) {
            deletedSongId = songId
        }
        override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
    }

    private class FakePlaybackController : PlaybackController {
        val mutableState = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> = mutableState
        var releaseCount = 0
        var stopCount = 0
        override fun playQueue(songs: List<Song>, startIndex: Int) = Unit
        override fun togglePlayPause() = Unit
        override fun stop() {
            stopCount++
        }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun setPlaybackMode(mode: PlaybackMode) = Unit
        override fun release() {
            releaseCount++
        }
    }

    private class FakeLyricsResolver(application: Application) : LyricsResolverApi {
        override val preferences = LyricsAutomationPreferences(application)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val releaseFirstSearch = CompletableDeferred<Unit>()
        private var searchCalls = 0

        override suspend fun resolveAutomatically(song: Song): LyricsResolution =
            LyricsResolution.NoResults

        override suspend fun resolve(song: Song, allowOnline: Boolean): LyricsResolution =
            LyricsResolution.NoResults

        override suspend fun searchOnline(song: Song, keywords: String?): LyricsResolution {
            searchCalls++
            if (searchCalls == 1) {
                firstSearchStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirstSearch.await() }
            }
            return LyricsResolution.OnlineChoices(listOf(match(song.id)))
        }

        override suspend fun applyOnline(
            songId: Long,
            result: RankedOnlineLyrics,
        ): LyricsResolution = LyricsResolution.NoResults

        private fun match(songId: Long) = RankedOnlineLyrics(
            record = LrclibLyricsRecord(
                id = songId,
                trackName = "Song $songId",
                artistName = "Artist",
                albumName = "Album",
                durationSeconds = 60.0,
                instrumental = false,
                plainLyrics = "line",
                syncedLyrics = null,
            ),
            score = 90,
            canAutoImport = true,
        )
    }

    private class FakeVideoAudioExtractor : VideoAudioExtractor {
        val requests = mutableListOf<VideoImportRequest>()
        val state = MutableStateFlow<MediaOperationState>(MediaOperationState.Idle)
        var enqueueCalls = 0
        var enqueueGate: CompletableDeferred<Unit>? = null
        var enqueueResult = true
        var stateOnRejectedEnqueue: MediaOperationState? = null
        var stateAfterCancel: MediaOperationState = MediaOperationState.Cancelled()

        override suspend fun enqueue(request: VideoImportRequest): Boolean {
            enqueueCalls++
            enqueueGate?.await()
            if (!enqueueResult) {
                stateOnRejectedEnqueue?.let { state.value = it }
                return false
            }
            if (state.value is MediaOperationState.Processing) return false
            requests += request
            state.value = MediaOperationState.Processing(request.title, 0, 1, 25)
            return true
        }

        override suspend fun cancel() {
            state.value = stateAfterCancel
        }

        override suspend fun currentState(): MediaOperationState = state.value
    }

    private class FakeRelocationCoordinator(
        private val application: Application,
    ) : SongRelocationCoordinator {
        var startCalls = 0
        var lastAuthorizationGranted: Boolean? = null
        val startGate = CompletableDeferred<Unit>()

        override suspend fun preview() = PlaylistMovePreview(3, 3_000L, 0)

        override suspend fun start(
            folderName: String,
            onState: (MediaOperationState) -> Unit,
        ): RelocationStep {
            startCalls++
            onState(MediaOperationState.Processing("song.mp3", 0, 3, 10))
            startGate.await()
            val waiting = MediaOperationState.AwaitingSystemAuthorization("需要系统授权", 1, 3)
            onState(waiting)
            val pendingIntent = PendingIntent.getActivity(
                application,
                91,
                Intent(application, application.javaClass),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RelocationStep.AwaitingAuthorization(
                MediaAuthorizationRequest(pendingIntent, waiting.message, 1, 3)
            )
        }

        override suspend fun resume(
            authorizationGranted: Boolean,
            onState: (MediaOperationState) -> Unit,
        ): RelocationStep {
            lastAuthorizationGranted = authorizationGranted
            val completed = MediaOperationState.Completed(
                MediaOperationSummary(moved = 2, cancelled = if (authorizationGranted) 0 else 1)
            )
            onState(completed)
            return RelocationStep.Finished(completed)
        }

        override suspend fun recover(onState: (MediaOperationState) -> Unit): RelocationStep? = null

        override suspend fun cancel(
            onState: (MediaOperationState) -> Unit,
        ): MediaOperationState = MediaOperationState.Cancelled().also(onState)
    }

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 60_000L,
        trackNumber = 0,
        dateAddedSeconds = id,
        contentUri = Uri.parse("content://songs/$id"),
        albumArtUri = null,
    )

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(20L)
        }
    }
}
