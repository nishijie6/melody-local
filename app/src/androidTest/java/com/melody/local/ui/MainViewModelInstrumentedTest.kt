package com.melody.local.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melody.local.data.DuplicatePlaylistNameException
import com.melody.local.data.MusicLibrary
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.Song
import com.melody.local.data.SongMetadataEditor
import com.melody.local.data.playbackDurationMs
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
import com.melody.local.playback.PlaybackQueueSource
import com.melody.local.playback.PlaybackUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
        waitUntil { fixture.viewModel.isLoading.value && music.loadCount == 1 }
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
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 3 }

        fixture.playlists.songIds.value = listOf(3L, 999L, 1L)
        fixture.viewModel.selectPlaylist(42L)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.map { it.id } == listOf(3L, 1L) }

        fixture.viewModel.selectPlaylist(null)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.isEmpty() }
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun playlistSortingUsesArtistThenTitleAndCanRestoreTheStoredOrder() = runBlocking {
        val first = song(1).copy(title = "Beta", artist = "Zulu")
        val second = song(2).copy(title = "Omega", artist = "Alpha")
        val third = song(3).copy(title = "alpha", artist = "alpha")
        val fixture = Fixture(music = FakeMusicLibrary { listOf(third, first, second) })
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        fixture.playlists.songIds.value = listOf(1L, 2L, 999L, 3L)
        fixture.viewModel.refreshSongs()
        fixture.viewModel.selectPlaylist(42L)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(1L, 2L, 3L) }

        fixture.viewModel.updatePlaylistSort(PlaylistSort.ARTIST)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(3L, 2L, 1L) }
        fixture.viewModel.updateSort(SongSort.RECENT)
        assertEquals(PlaylistSort.ARTIST, fixture.viewModel.playlistSort.value)
        fixture.viewModel.updatePlaylistSort(PlaylistSort.TITLE)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(3L, 1L, 2L) }
        fixture.viewModel.updatePlaylistSort(PlaylistSort.ADDED)
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(1L, 2L, 3L) }
        assertEquals(listOf(1L, 2L, 999L, 3L), fixture.playlists.songIds.value)
        assertEquals(SongSort.RECENT, fixture.viewModel.sort.value)
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun recreatingTheViewModelRestoresTheLastPlaylistAndSortAfterLeavingItsDetail() = runBlocking {
        val preferencesName = "playlist_restore_${UUID.randomUUID()}"
        val original = Fixture(preferencesName = preferencesName)
        original.viewModel.selectPlaylist(1L)
        original.viewModel.selectPlaylist(42L)
        original.viewModel.updatePlaylistSort(PlaylistSort.ARTIST)
        original.viewModel.selectPlaylist(null)
        assertEquals(null, original.viewModel.selectedPlaylistId.value)
        original.clear(clearPreferences = false)

        val restored = Fixture(preferencesName = preferencesName)
        assertEquals(42L, restored.viewModel.initialPlaylistId)
        assertEquals(42L, restored.viewModel.selectedPlaylistId.value)
        assertEquals(PlaylistSort.ARTIST, restored.viewModel.playlistSort.value)
        waitUntil { restored.viewModel.playlists.value.isNotEmpty() }
        assertEquals(42L, restored.viewModel.selectedPlaylistId.value)
        restored.clear()
    }

    @Test
    fun missingRememberedPlaylistIsClearedOnlyAfterTheRepositoryLoads() = runBlocking {
        val preferencesName = "playlist_missing_${UUID.randomUUID()}"
        val preferences = PlaylistPreferences(application, preferencesName)
        preferences.lastOpenedPlaylistId = 99L
        val loaded = CompletableDeferred<Unit>()
        val fixture = Fixture(
            preferencesName = preferencesName,
            playlists = FakePlaylistStore(loadGate = loaded),
        )
        delay(50)
        assertTrue(fixture.viewModel.playlists.value.isEmpty())
        assertEquals(99L, fixture.viewModel.selectedPlaylistId.value)
        assertEquals(99L, preferences.lastOpenedPlaylistId)

        loaded.complete(Unit)
        waitUntil { fixture.viewModel.playlists.value.isNotEmpty() }
        assertEquals(null, fixture.viewModel.selectedPlaylistId.value)
        assertEquals(null, preferences.lastOpenedPlaylistId)
        fixture.clear()
    }

    @Test
    fun emptyLoadedPlaylistDirectoryClearsTheRememberedSelectionAfterLoading() = runBlocking {
        val preferencesName = "playlist_empty_directory_${UUID.randomUUID()}"
        val preferences = PlaylistPreferences(application, preferencesName)
        preferences.lastOpenedPlaylistId = 42L
        val loaded = CompletableDeferred<Unit>()
        val playlists = FakePlaylistStore(loadGate = loaded).apply {
            summaries.value = emptyList()
        }
        val fixture = Fixture(preferencesName = preferencesName, playlists = playlists)
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        try {
            delay(50L)
            assertTrue(fixture.viewModel.playlists.value.isEmpty())
            assertEquals(42L, fixture.viewModel.selectedPlaylistId.value)
            assertEquals(42L, preferences.lastOpenedPlaylistId)

            loaded.complete(Unit)
            waitUntil {
                fixture.viewModel.selectedPlaylistId.value == null &&
                    preferences.lastOpenedPlaylistId == null
            }
            assertTrue(fixture.viewModel.playlists.value.isEmpty())
            waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.isEmpty() }
        } finally {
            loaded.complete(Unit)
            collection.cancelAndJoin()
            fixture.clear()
        }
    }

    @Test
    fun returningToTheFirstPlaylistIgnoresTheDelayedSecondPlaylistResult() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val secondCancellationObserved = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val playlists = FakePlaylistStore().apply {
            songIdsFor(42L).value = listOf(1L)
            songIdsFor(1L).value = listOf(2L)
            songIdFlows[1L] = flow {
                secondStarted.complete(Unit)
                try {
                    val result = try {
                        releaseSecond.await()
                        songIdsFor(1L).value
                    } catch (_: CancellationException) {
                        // Model a repository read returning after its caller was cancelled.
                        secondCancellationObserved.complete(Unit)
                        withContext(NonCancellable) {
                            releaseSecond.await()
                            songIdsFor(1L).value
                        }
                    }
                    emit(result)
                } finally {
                    secondFinished.complete(Unit)
                }
            }
        }
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(song(1), song(2)) },
            playlists = playlists,
        )
        val observed = mutableListOf<PlaylistContents>()
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect(observed::add) }
        try {
            fixture.viewModel.refreshSongs()
            fixture.viewModel.selectPlaylist(42L)
            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                contents.playlistId == 42L && !contents.isLoading &&
                    contents.songs.map(Song::id) == listOf(1L)
            }

            fixture.viewModel.selectPlaylist(1L)
            withTimeout(5_000L) { secondStarted.await() }
            val afterReturningIndex = observed.size
            fixture.viewModel.selectPlaylist(42L)
            withTimeout(5_000L) { secondCancellationObserved.await() }
            releaseSecond.complete(Unit)
            withTimeout(5_000L) { secondFinished.await() }
            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                playlists.observedPlaylistIds.count { it == 42L } >= 2 &&
                    contents.playlistId == 42L && !contents.isLoading &&
                    contents.songs.map(Song::id) == listOf(1L)
            }
            delay(50L)
            assertFalse(observed.drop(afterReturningIndex).any { contents ->
                contents.playlistId == 1L && contents.songs.any { it.id == 2L }
            })
            assertEquals(listOf(1L), playlists.songIdsFor(42L).value)
            assertEquals(listOf(2L), playlists.songIdsFor(1L).value)
            assertEquals(42L, fixture.preferences.lastOpenedPlaylistId)
        } finally {
            releaseSecond.complete(Unit)
            collection.cancelAndJoin()
            fixture.clear()
        }
    }

    @Test
    fun playlistPlaybackRejectsOldSelectionSortAndSnapshotInstances() = runBlocking {
        val first = song(1).copy(artist = "Zulu")
        val second = song(2).copy(artist = "Alpha")
        val third = song(3)
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val playlists = FakePlaylistStore().apply {
            songIdsFor(42L).value = listOf(first.id, second.id)
            songIdsFor(1L).value = listOf(third.id)
            songIdFlows[1L] = flow {
                secondStarted.complete(Unit)
                releaseSecond.await()
                emit(songIdsFor(1L).value)
            }
        }
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(first, second, third) },
            playlists = playlists,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        try {
            fixture.viewModel.refreshSongs()
            fixture.viewModel.selectPlaylist(42L)
            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                contents.playlistId == 42L && !contents.isLoading &&
                    contents.songs.map(Song::id) == listOf(first.id, second.id)
            }
            val original = fixture.viewModel.selectedPlaylistContents.value

            instrumentation.runOnMainSync {
                fixture.viewModel.selectPlaylist(1L)
                fixture.viewModel.playPlaylistSongs(original, 0, "Selected")
            }
            withTimeout(5_000L) { secondStarted.await() }
            assertEquals(0, fixture.player.playCalls)

            fixture.viewModel.selectPlaylist(42L)
            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                playlists.observedPlaylistIds.count { it == 42L } >= 2 &&
                    contents !== original && contents.playlistId == 42L && !contents.isLoading &&
                    contents.songs.map(Song::id) == listOf(first.id, second.id)
            }
            val reloaded = fixture.viewModel.selectedPlaylistContents.value
            instrumentation.runOnMainSync {
                // The old snapshot has the same ID, sort and songs, but belongs to a prior visit.
                fixture.viewModel.playPlaylistSongs(original, 0, "Selected")
                fixture.viewModel.updatePlaylistSort(PlaylistSort.ARTIST)
                fixture.viewModel.playPlaylistSongs(reloaded, 0, "Selected")
            }
            assertEquals(0, fixture.player.playCalls)

            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                contents.playlistId == 42L && contents.sort == PlaylistSort.ARTIST &&
                    !contents.isLoading && contents.songs.map(Song::id) == listOf(second.id, first.id)
            }
            val current = fixture.viewModel.selectedPlaylistContents.value
            instrumentation.runOnMainSync {
                fixture.viewModel.playPlaylistSongs(current, 1, "Selected")
            }
            assertEquals(1, fixture.player.playCalls)
            assertEquals(listOf(second.id, first.id), fixture.player.lastPlayedSongs.map(Song::id))
            assertEquals(1, fixture.player.lastStartIndex)
            assertEquals(PlaybackQueueSource.playlist(42L, "Selected"), fixture.player.lastSource)
        } finally {
            releaseSecond.complete(Unit)
            collection.cancelAndJoin()
            fixture.clear()
        }
    }

    @Test
    fun emptyRememberedPlaylistAndRenamingKeepTheSameStartupIdentity() = runBlocking {
        val preferencesName = "playlist_empty_${UUID.randomUUID()}"
        val preferences = PlaylistPreferences(application, preferencesName)
        preferences.lastOpenedPlaylistId = 42L
        val fixture = Fixture(preferencesName = preferencesName)
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        waitUntil { fixture.viewModel.playlists.value.isNotEmpty() }
        assertTrue(fixture.viewModel.selectedPlaylistContents.value.songs.isEmpty())
        assertEquals(42L, fixture.viewModel.selectedPlaylistId.value)

        fixture.playlists.summaries.value = fixture.playlists.summaries.value.map {
            if (it.id == 42L) it.copy(name = "Renamed empty playlist") else it
        }
        waitUntil { fixture.viewModel.playlists.value.any { it.name == "Renamed empty playlist" } }
        assertEquals(42L, fixture.viewModel.selectedPlaylistId.value)
        assertEquals(42L, preferences.lastOpenedPlaylistId)
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun deletingTheRememberedPlaylistAfterLeavingItsDetailClearsTheSavedChoice() = runBlocking {
        val fixture = Fixture()
        fixture.viewModel.selectPlaylist(42L)
        fixture.viewModel.selectPlaylist(null)
        fixture.viewModel.deletePlaylist(1L)
        waitUntil { fixture.playlists.deletedId == 1L }
        assertEquals(42L, fixture.preferences.lastOpenedPlaylistId)

        fixture.viewModel.deletePlaylist(42L)
        waitUntil { fixture.preferences.lastOpenedPlaylistId == null }
        assertEquals(null, fixture.viewModel.selectedPlaylistId.value)
        fixture.clear()
    }

    @Test
    fun unknownSavedSortAndNonpositivePlaylistIdUseSafeDefaults() {
        val preferencesName = "playlist_invalid_${UUID.randomUUID()}"
        application.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putLong(PlaylistPreferences.KEY_LAST_OPENED_PLAYLIST, -1L)
            .putString(PlaylistPreferences.KEY_SORT, "unknown_future_sort")
            .commit()
        val fixture = Fixture(preferencesName = preferencesName)
        assertEquals(null, fixture.viewModel.initialPlaylistId)
        assertEquals(null, fixture.viewModel.selectedPlaylistId.value)
        assertEquals(PlaylistSort.ADDED, fixture.viewModel.playlistSort.value)
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
        val messages = mutableListOf<String>()
        val stateCollection = launch { fixture.viewModel.lyrics.collect() }
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
        withTimeout(5_000L) { resolver.firstSearchStarted.await() }

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

    @Test
    fun relocationBlocksEditingStaleSongIdentityUntilTheForcedRefreshCompletes() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application)
        val original = song(1)
        val relocated = song(101)
        val refreshedSongs = CompletableDeferred<List<Song>>()
        var saves = 0
        val music = FakeMusicLibrary { listOf(original) }
        val fixture = Fixture(
            music = music,
            relocation = coordinator,
            songEditor = SongMetadataEditor { song, _, _, _, _ ->
                saves++
                song
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.singleOrNull()?.id == original.id }
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value != null }
        fixture.viewModel.startPlaylistMove("歌单汇总")
        fixture.viewModel.openSongEditor(original)
        assertEquals(null, fixture.viewModel.songEditorDraft.value)
        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }

        music.loader = { refreshedSongs.await() }
        fixture.viewModel.resumePlaylistMove(false)
        waitUntil { fixture.viewModel.playlistMoveState.value is MediaOperationState.Completed }

        fixture.viewModel.openSongEditor(original)
        fixture.viewModel.saveSongMetadata(original.id, "Stale", "A", "B", null)
        assertEquals(null, fixture.viewModel.songEditorDraft.value)
        assertEquals(0, saves)

        refreshedSongs.complete(listOf(relocated))
        waitUntil {
            !fixture.viewModel.isLoading.value &&
                fixture.viewModel.allSongs.value.singleOrNull()?.id == relocated.id
        }
        fixture.viewModel.openSongEditor(relocated)
        assertEquals(relocated.id, fixture.viewModel.songEditorDraft.value?.id)
        fixture.clear()
    }

    @Test
    fun recoverableRelocationFailureKeepsEditingBlockedUntilForcedRefreshSucceeds() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application).apply {
            resumeResult = MediaOperationState.Failed(
                "歌曲文件已安全移动，但关联信息尚未全部提交"
            )
        }
        val original = song(1)
        val relocated = song(101)
        val music = FakeMusicLibrary { listOf(original) }
        var saves = 0
        val fixture = Fixture(
            music = music,
            relocation = coordinator,
            songEditor = SongMetadataEditor { song, _, _, _, _ ->
                saves++
                song
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.singleOrNull()?.id == original.id }
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value != null }
        fixture.viewModel.startPlaylistMove("歌单汇总")
        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }

        val lifecycleRefresh = CompletableDeferred<List<Song>>()
        val postFailureRefresh = CompletableDeferred<List<Song>>()
        music.loader = { lifecycleRefresh.await() }
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.isLoading.value && music.loadCount == 2 }
        music.loader = { postFailureRefresh.await() }
        fixture.viewModel.resumePlaylistMove(true)
        waitUntil {
            fixture.viewModel.playlistMoveState.value is MediaOperationState.Failed &&
                fixture.viewModel.isLoading.value &&
                music.loadCount == 3
        }

        lifecycleRefresh.complete(listOf(original))
        delay(50L)
        fixture.viewModel.openSongEditor(original)
        fixture.viewModel.saveSongMetadata(original.id, "Stale", "A", "B", null)
        assertEquals(null, fixture.viewModel.songEditorDraft.value)
        assertEquals(0, saves)

        postFailureRefresh.complete(listOf(relocated))
        waitUntil {
            !fixture.viewModel.isLoading.value &&
                fixture.viewModel.allSongs.value.singleOrNull()?.id == relocated.id
        }
        fixture.viewModel.openSongEditor(relocated)
        assertEquals(relocated.id, fixture.viewModel.songEditorDraft.value?.id)
        fixture.clear()
    }

    @Test
    fun failedRelocationCancellationForcesRefreshBeforeEditingCanResume() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application).apply {
            cancelResult = MediaOperationState.Failed(
                "安全终止尚未完成，关联信息仍待恢复"
            )
        }
        val original = song(1)
        val music = FakeMusicLibrary { listOf(original) }
        var saves = 0
        val fixture = Fixture(
            music = music,
            relocation = coordinator,
            songEditor = SongMetadataEditor { song, _, _, _, _ ->
                saves++
                song
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.singleOrNull()?.id == original.id }
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value != null }
        fixture.viewModel.startPlaylistMove("歌单汇总")
        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }

        val postFailureRefresh = CompletableDeferred<List<Song>>()
        music.loader = { postFailureRefresh.await() }
        fixture.viewModel.cancelPlaylistMove()
        waitUntil {
            fixture.viewModel.playlistMoveState.value is MediaOperationState.Failed &&
                fixture.viewModel.isLoading.value &&
                music.loadCount == 2
        }
        fixture.viewModel.openSongEditor(original)
        fixture.viewModel.saveSongMetadata(original.id, "Stale", "A", "B", null)
        assertEquals(null, fixture.viewModel.songEditorDraft.value)
        assertEquals(0, saves)

        postFailureRefresh.complete(listOf(original))
        waitUntil { !fixture.viewModel.isLoading.value }
        fixture.viewModel.openSongEditor(original)
        assertEquals(original.id, fixture.viewModel.songEditorDraft.value?.id)
        fixture.clear()
    }

    @Test
    fun failedPostRelocationRefreshRequiresSuccessfulRetryBeforeEditing() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application)
        val original = song(1)
        val relocated = song(101)
        val music = FakeMusicLibrary { listOf(original) }
        val fixture = Fixture(
            music = music,
            relocation = coordinator,
            songEditor = SongMetadataEditor { song, _, _, _, _ -> song },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.singleOrNull()?.id == original.id }
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value != null }
        fixture.viewModel.startPlaylistMove("歌单汇总")
        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }

        music.loader = { throw IOException("MediaStore unavailable") }
        fixture.viewModel.resumePlaylistMove(false)
        waitUntil {
            fixture.viewModel.playlistMoveState.value is MediaOperationState.Completed &&
                !fixture.viewModel.isLoading.value &&
                fixture.viewModel.libraryError.value != null
        }
        fixture.viewModel.openSongEditor(original)
        assertEquals(null, fixture.viewModel.songEditorDraft.value)

        music.loader = { listOf(relocated) }
        fixture.viewModel.refreshSongs()
        waitUntil {
            !fixture.viewModel.isLoading.value &&
                fixture.viewModel.allSongs.value.singleOrNull()?.id == relocated.id
        }
        fixture.viewModel.openSongEditor(relocated)
        assertEquals(relocated.id, fixture.viewModel.songEditorDraft.value?.id)
        fixture.clear()
    }

    @Test
    fun forcedPostRelocationRefreshSupersedesAnOlderInFlightLibraryRead() = runBlocking {
        val coordinator = FakeRelocationCoordinator(application)
        val original = song(1)
        val relocated = song(101)
        val staleRead = CompletableDeferred<List<Song>>()
        val music = FakeMusicLibrary { listOf(original) }
        val fixture = Fixture(
            music = music,
            relocation = coordinator,
            songEditor = SongMetadataEditor { song, _, _, _, _ -> song },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.singleOrNull()?.id == original.id }

        music.loader = { staleRead.await() }
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.isLoading.value && music.loadCount == 2 }
        fixture.viewModel.loadPlaylistMovePreview()
        waitUntil { fixture.viewModel.playlistMovePreview.value != null }
        fixture.viewModel.startPlaylistMove("歌单汇总")
        coordinator.startGate.complete(Unit)
        waitUntil { fixture.viewModel.authorizationRequest.value != null }

        music.loader = { listOf(relocated) }
        fixture.viewModel.resumePlaylistMove(false)
        waitUntil {
            !fixture.viewModel.isLoading.value &&
                music.loadCount == 3 &&
                fixture.viewModel.allSongs.value.singleOrNull()?.id == relocated.id
        }
        staleRead.complete(listOf(original))
        delay(50L)

        assertEquals(relocated.id, fixture.viewModel.allSongs.value.single().id)
        fixture.viewModel.openSongEditor(relocated)
        assertEquals(relocated.id, fixture.viewModel.songEditorDraft.value?.id)
        fixture.clear()
    }

    @Test
    fun songEditUpdatesLibraryAndPlaylistWithoutStoppingOrRetargetingPlayback() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(song(1), song(2)) },
            songEditor = SongMetadataEditor { original, title, artist, album, _ ->
                saves++
                gate.await()
                original.copy(title = title, artist = artist, album = album)
            },
        )
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        fixture.playlists.songIds.value = listOf(2, 1)
        fixture.viewModel.selectPlaylist(42)
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 2 }
        fixture.viewModel.openSongEditor(song(1))
        fixture.player.mutableState.value = PlaybackUiState(mediaId = 2, positionMs = 5000, isPlaying = true)
        fixture.viewModel.saveSongMetadata(1, "Edited", "New artist", "New album", null)
        fixture.viewModel.saveSongMetadata(1, "Duplicate", "A", "B", null)
        waitUntil { saves == 1 }
        fixture.viewModel.dismissSongEditor()
        assertEquals(1L, fixture.viewModel.songEditorDraft.value?.id)
        gate.complete(Unit)
        waitUntil { fixture.viewModel.songEditorDraft.value == null }
        waitUntil { fixture.viewModel.selectedPlaylistContents.value.songs.lastOrNull()?.title == "Edited" }
        assertEquals(listOf(2L, 1L), fixture.viewModel.selectedPlaylistContents.value.songs.map { it.id })
        assertEquals(2L, fixture.player.state.value.mediaId)
        assertEquals(5000L, fixture.player.state.value.positionMs)
        assertEquals(0, fixture.player.stopCount)
        collection.cancel()
        fixture.clear()
    }

    @Test
    fun editingAnArtistReordersThePlaylistWithoutChangingMembershipOrPlayback() = runBlocking {
        val first = song(1).copy(artist = "Zulu")
        val second = song(2).copy(artist = "Bravo")
        val third = song(3).copy(artist = "Charlie")
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(third, first, second) },
            songEditor = SongMetadataEditor { original, title, artist, album, _ ->
                original.copy(title = title, artist = artist, album = album)
            },
        )
        val collection = launch { fixture.viewModel.selectedPlaylistContents.collect() }
        try {
            fixture.playlists.songIds.value = listOf(3L, 1L, 2L)
            fixture.viewModel.updatePlaylistSort(PlaylistSort.ARTIST)
            fixture.viewModel.selectPlaylist(42L)
            fixture.viewModel.refreshSongs()
            waitUntil {
                val contents = fixture.viewModel.selectedPlaylistContents.value
                contents.sort == PlaylistSort.ARTIST && !contents.isLoading &&
                    contents.songs.map(Song::id) == listOf(2L, 3L, 1L)
            }
            val playback = PlaybackUiState(
                mediaId = second.id,
                positionMs = 5_000L,
                isPlaying = true,
                playbackMode = PlaybackMode.LOOP,
                queueSource = PlaybackQueueSource.playlist(42L, "Selected"),
            )
            fixture.player.mutableState.value = playback
            fixture.viewModel.openSongEditor(first)
            fixture.viewModel.saveSongMetadata(first.id, first.title, "Alpha", first.album, null)
            waitUntil {
                fixture.viewModel.songEditorDraft.value == null &&
                    fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(1L, 2L, 3L)
            }

            assertEquals("Alpha", fixture.viewModel.selectedPlaylistContents.value.songs.first().artist)
            assertEquals(listOf(3L, 1L, 2L), fixture.playlists.songIds.value)
            assertEquals(playback, fixture.player.state.value)
            assertEquals(0, fixture.player.stopCount)

            fixture.viewModel.updatePlaylistSort(PlaylistSort.ADDED)
            waitUntil {
                fixture.viewModel.selectedPlaylistContents.value.songs.map(Song::id) == listOf(3L, 1L, 2L)
            }
            assertEquals(playback, fixture.player.state.value)
        } finally {
            collection.cancelAndJoin()
            fixture.clear()
        }
    }

    @Test
    fun songEditValidatesAndPublishesPlaybackRange() = runBlocking {
        var inputToEditor: Song? = null
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(song(1)) },
            songEditor = SongMetadataEditor { original, title, artist, album, _ ->
                inputToEditor = original
                original.copy(title = title, artist = artist, album = album)
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 1 }
        fixture.viewModel.openSongEditor(song(1))

        fixture.viewModel.saveSongEdits(
            songId = 1L,
            title = "Edited",
            artist = "Artist",
            album = "Album",
            replacementArtwork = null,
            playbackStartMs = 12_000L,
            playbackEndMs = 50_000L,
        )
        waitUntil { fixture.viewModel.songEditorDraft.value == null }

        assertEquals(12_000L, inputToEditor?.playbackStartMs)
        assertEquals(50_000L, inputToEditor?.playbackEndMs)
        assertEquals(38_000L, fixture.viewModel.allSongs.value.single().playbackDurationMs())
        fixture.clear()
    }

    @Test
    fun failedSongEditKeepsDraftAndCanBeRetried() = runBlocking {
        var fail = true
        val fixture = Fixture(
            music = FakeMusicLibrary { listOf(song(1)) },
            songEditor = SongMetadataEditor { original, title, _, _, _ ->
                if (fail) throw IllegalStateException("storage unavailable")
                original.copy(title = title)
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 1 }
        fixture.viewModel.openSongEditor(song(1))
        fixture.viewModel.saveSongMetadata(1, "Edited", "A", "B", null)
        waitUntil { fixture.viewModel.songEditError.value != null }
        assertEquals("Song 1", fixture.viewModel.allSongs.value.single().title)
        assertEquals(1L, fixture.viewModel.songEditorDraft.value?.id)
        fail = false
        fixture.viewModel.saveSongMetadata(1, "Edited", "A", "B", null)
        waitUntil { fixture.viewModel.songEditorDraft.value == null }
        assertEquals("Edited", fixture.viewModel.allSongs.value.single().title)
        fixture.clear()
    }

    @Test
    fun refreshStartedBeforeSaveCannotRestoreOldMetadata() = runBlocking {
        val original = song(1)
        var storedSong = original
        val music = FakeMusicLibrary { listOf(storedSong) }
        val fixture = Fixture(
            music = music,
            songEditor = SongMetadataEditor { value, title, _, _, _ ->
                value.copy(title = title).also { storedSong = it }
            },
        )
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.allSongs.value.size == 1 && !fixture.viewModel.isLoading.value }
        fixture.viewModel.openSongEditor(original)
        val staleRead = CompletableDeferred<List<Song>>()
        music.loader = { staleRead.await() }
        fixture.viewModel.refreshSongs()
        waitUntil { fixture.viewModel.isLoading.value }
        fixture.viewModel.saveSongMetadata(1, "Edited", "A", "B", null)
        waitUntil { fixture.viewModel.songEditorDraft.value == null }
        music.loader = { listOf(storedSong) }
        staleRead.complete(listOf(original))
        waitUntil { !fixture.viewModel.isLoading.value }
        assertEquals("Edited", fixture.viewModel.allSongs.value.single().title)
        assertEquals(3, music.loadCount)
        fixture.clear()
    }

    private inner class Fixture(
        music: FakeMusicLibrary = FakeMusicLibrary { emptyList() },
        videoExtractor: VideoAudioExtractor? = null,
        relocation: SongRelocationCoordinator? = null,
        lyricsResolver: LyricsResolverApi? = null,
        songEditor: SongMetadataEditor? = null,
        private val preferencesName: String = "view_model_playlist_${UUID.randomUUID()}",
        val playlists: FakePlaylistStore = FakePlaylistStore(),
    ) {
        val preferences = PlaylistPreferences(application, preferencesName)
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
                    songEditor,
                    preferences,
                ) as T
            },
        )[MainViewModel::class.java]

        fun clear(clearPreferences: Boolean = true) {
            store.clear()
            if (clearPreferences) application.deleteSharedPreferences(preferencesName)
        }
    }

    private class FakeMusicLibrary(var loader: suspend () -> List<Song>) : MusicLibrary {
        var loadCount = 0
        override suspend fun loadSongs(): List<Song> {
            loadCount++
            return loader()
        }
    }

    private class FakePlaylistStore(private val loadGate: CompletableDeferred<Unit>? = null) : PlaylistStore {
        val summaries = MutableStateFlow(
            listOf(
                PlaylistSummary(id = 1L, name = "First", createdAt = 1L, songCount = 0),
                PlaylistSummary(id = 42L, name = "Selected", createdAt = 2L, songCount = 0),
            )
        )
        override val playlists: Flow<List<PlaylistSummary>> = flow {
            loadGate?.await()
            emitAll(summaries)
        }
        private val memberships = ConcurrentHashMap<Long, MutableStateFlow<List<Long>>>()
        val songIdFlows = ConcurrentHashMap<Long, Flow<List<Long>>>()
        val songIds: MutableStateFlow<List<Long>> get() = songIdsFor(42L)
        fun songIdsFor(playlistId: Long): MutableStateFlow<List<Long>> =
            memberships.getOrPut(playlistId) { MutableStateFlow(emptyList()) }
        val observedPlaylistIds = CopyOnWriteArrayList<Long?>()
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
            summaries.value = summaries.value.filterNot { it.id == playlistId }
        }

        override suspend fun addSong(playlistId: Long, songId: Long): Boolean = addResult
        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit
        override suspend fun getAllSongIds(): List<Long> = memberships.values.flatMap { it.value }.distinct()
        override suspend fun remapSongIds(remaps: Map<Long, Long>) {
            memberships.values.forEach { ids -> ids.value = ids.value.map { remaps[it] ?: it } }
        }
        override fun observeSongIds(playlistId: Long): Flow<List<Long>> {
            observedPlaylistIds += playlistId
            return songIdFlows[playlistId] ?: songIdsFor(playlistId)
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
        var playCalls = 0
            private set
        var lastPlayedSongs = emptyList<Song>()
            private set
        var lastStartIndex: Int? = null
            private set
        var lastSource: PlaybackQueueSource? = null
            private set
        override fun playQueue(
            songs: List<Song>,
            startIndex: Int,
            source: PlaybackQueueSource,
        ) {
            playCalls++
            lastPlayedSongs = songs.toList()
            lastStartIndex = startIndex
            lastSource = source
        }
        override fun togglePlayPause() = Unit
        override fun stop() {
            stopCount++
        }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun seekToQueueItem(index: Int) = Unit
        override fun setPlaybackMode(mode: PlaybackMode) = Unit
        override fun setAudioMixingEnabled(enabled: Boolean) = Unit
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
        var resumeResult: MediaOperationState = MediaOperationState.Completed(
            MediaOperationSummary(moved = 2, cancelled = 1)
        )
        var cancelResult: MediaOperationState = MediaOperationState.Cancelled()

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
            val result = when (val configured = resumeResult) {
                is MediaOperationState.Completed -> configured.copy(
                    summary = configured.summary.copy(
                        cancelled = if (authorizationGranted) 0 else configured.summary.cancelled,
                    )
                )
                else -> configured
            }
            onState(result)
            return RelocationStep.Finished(result)
        }

        override suspend fun recover(onState: (MediaOperationState) -> Unit): RelocationStep? = null

        override suspend fun cancel(
            onState: (MediaOperationState) -> Unit,
        ): MediaOperationState = cancelResult.also(onState)
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
