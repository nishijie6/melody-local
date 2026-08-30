package com.melody.local.ui

import android.app.Application
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
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import com.melody.local.playback.PlaybackController
import com.melody.local.playback.PlaybackMode
import com.melody.local.playback.PlaybackUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private inner class Fixture(
        music: FakeMusicLibrary = FakeMusicLibrary { emptyList() },
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

        override suspend fun load(songId: Long): ParsedLyrics? {
            loadGate?.await()
            return loaded[songId]?.getOrThrow()
        }
        override suspend fun import(songId: Long, uri: Uri): ParsedLyrics = importResult.getOrThrow()
        override suspend fun delete(songId: Long) {
            deletedSongId = songId
        }
    }

    private class FakePlaybackController : PlaybackController {
        val mutableState = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> = mutableState
        var releaseCount = 0
        override fun playQueue(songs: List<Song>, startIndex: Int) = Unit
        override fun togglePlayPause() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun setPlaybackMode(mode: PlaybackMode) = Unit
        override fun release() {
            releaseCount++
        }
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

