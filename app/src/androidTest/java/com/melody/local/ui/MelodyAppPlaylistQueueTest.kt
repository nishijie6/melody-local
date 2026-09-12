package com.melody.local.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.MusicLibrary
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.Song
import com.melody.local.lyrics.LyricsStore
import com.melody.local.playback.PlaybackController
import com.melody.local.playback.PlaybackMode
import com.melody.local.playback.PlaybackQueueItem
import com.melody.local.playback.PlaybackQueueSource
import com.melody.local.playback.PlaybackUiState
import com.melody.local.ui.theme.MelodyTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MelodyAppPlaylistQueueTest {
    @get:Rule
    val compose = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var fixture: Fixture

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.clear()
    }

    @Test
    fun playlistPlaybackShowsSourceAndOrderedQueueAndSelectingAnotherItemKeepsTheQueue() {
        val first = song(101L, "队列第一首")
        val second = song(102L, "队列第二首")
        fixture = Fixture(
            librarySongs = listOf(second, first),
            playlistSongIds = listOf(first.id, second.id),
        )

        setContentAndLoad()

        compose.onNodeWithText("歌单").performClick()
        compose.onNodeWithText(PLAYLIST_NAME).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.viewModel.selectedPlaylistSongs.value.map(Song::id) ==
                listOf(first.id, second.id)
        }

        compose.onNodeWithText(first.title).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.mediaId == first.id &&
                fixture.player.state.value.queueIndex == 0
        }
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.waitUntil(timeoutMillis = 5_000L) {
            !fixture.player.state.value.isPlaying
        }
        compose.onNodeWithTag("mini-player").performClick()

        compose.onNodeWithTag("now-playing-source")
            .assertIsDisplayed()
            .assertTextEquals("来自歌单：$PLAYLIST_NAME")
        compose.onNodeWithTag("now-playing-tab-queue").performClick()
        compose.onNodeWithTag("now-playing-queue").assertIsDisplayed()
        assertQueueItem(index = 0, title = first.title)
        assertQueueItem(index = 1, title = second.title)
        compose.onNodeWithTag("now-playing-queue-item-0").assertIsSelected()
        compose.onNodeWithTag("now-playing-queue-item-1").assertIsNotSelected()

        compose.onNodeWithTag("now-playing-queue-item-1").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.mediaId == second.id &&
                fixture.player.state.value.queueIndex == 1
        }
        compose.onNodeWithTag("now-playing-queue-item-0").assertIsNotSelected()
        compose.onNodeWithTag("now-playing-queue-item-1").assertIsSelected()
        compose.onNodeWithTag("now-playing-source")
            .assertTextEquals("来自歌单：$PLAYLIST_NAME")
        compose.runOnIdle {
            assertEquals(1, fixture.player.queueReplacementCount)
            assertEquals(listOf(1), fixture.player.selectedQueueIndices)
            assertEquals(
                listOf(first.id, second.id),
                fixture.player.state.value.queueItems.map { it.mediaId },
            )
            assertEquals(
                PlaybackQueueSource.playlist(PLAYLIST_ID, PLAYLIST_NAME),
                fixture.player.state.value.queueSource,
            )
            assertFalse(fixture.player.state.value.isPlaying)
        }

        compose.onNodeWithContentDescription("收起播放器").performClick()
        compose.onAllNodesWithTag("now-playing-source").assertCountEquals(0)
        compose.onNodeWithText(PLAYLIST_NAME).assertIsDisplayed()
        compose.onNodeWithText("2 首歌曲").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test
    fun tappingTheSameSongFromAnotherSourceStartsTheVisiblePlaylistQueue() {
        val first = song(101L, "共享歌曲")
        val second = song(102L, "歌单下一首")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()
        compose.runOnIdle {
            fixture.player.playQueue(
                listOf(first),
                startIndex = 0,
                source = PlaybackQueueSource.library(),
            )
        }

        compose.onNodeWithText("歌单").performClick()
        compose.onNodeWithText(PLAYLIST_NAME).performClick()
        compose.onNodeWithTag("playlist-song-${first.id}").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.queueSource ==
                PlaybackQueueSource.playlist(PLAYLIST_ID, PLAYLIST_NAME) &&
                fixture.player.queueReplacementCount == 2
        }
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.onNodeWithTag("mini-player").performClick()

        compose.onNodeWithTag("now-playing-source")
            .assertTextEquals("来自歌单：$PLAYLIST_NAME")
        compose.onNodeWithTag("now-playing-tab-queue").performClick()
        assertQueueItem(0, first.title)
        assertQueueItem(1, second.title)
    }

    @Test
    fun tappingTheSameSongFromAPlaylistStartsTheVisibleLibraryQueue() {
        val first = song(101L, "共享歌曲")
        val second = song(102L, "曲库下一首")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()
        compose.runOnIdle {
            fixture.player.playQueue(
                listOf(first),
                startIndex = 0,
                source = PlaybackQueueSource.playlist(PLAYLIST_ID, PLAYLIST_NAME),
            )
        }

        compose.onNodeWithTag("library-song-${first.id}").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.queueSource == PlaybackQueueSource.library() &&
                fixture.player.queueReplacementCount == 2
        }
        compose.runOnIdle {
            assertEquals(
                setOf(first.id, second.id),
                fixture.player.state.value.queueItems.map { it.mediaId }.toSet(),
            )
        }
    }

    @Test
    fun tappingTheSameSongAfterFilteringReplacesTheFullLibraryQueue() {
        val first = song(101L, "共同独有歌曲")
        val second = song(102L, "共同歌曲")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()
        compose.runOnIdle {
            fixture.player.playQueue(
                listOf(first, second),
                startIndex = 0,
                source = PlaybackQueueSource.library(),
            )
        }

        val search = compose.onNodeWithTag("library-search")
        search.performTextInput("独有")
        compose.onNodeWithTag("library-song-${first.id}").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.queueSource == PlaybackQueueSource.library("搜索结果") &&
                fixture.player.queueReplacementCount == 2
        }
        compose.runOnIdle {
            assertEquals(listOf(first.id), fixture.player.state.value.queueItems.map { it.mediaId })
        }

        compose.onNodeWithTag("library-search").performTextClearance()
        compose.onNodeWithTag("library-search").performTextInput("共同")
        compose.onNodeWithTag("library-song-${first.id}").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.queueSource == PlaybackQueueSource.library("搜索结果") &&
                fixture.player.queueReplacementCount == 3 &&
                fixture.player.state.value.queueItems.size == 2
        }
        compose.runOnIdle {
            assertEquals(
                setOf(first.id, second.id),
                fixture.player.state.value.queueItems.map { it.mediaId }.toSet(),
            )
        }
    }

    @Test
    fun openingALongQueueInitiallyRevealsTheCurrentSong() {
        val queue = (1L..30L).map { index ->
            PlaybackQueueItem(
                mediaId = 10_000L + index,
                title = "长队列歌曲 ${index.toString().padStart(2, '0')}",
                artist = "测试歌手",
                artworkUri = null,
            )
        }
        compose.setContent {
            MelodyTheme {
                PlaybackQueuePanel(
                    playback = PlaybackUiState(
                        mediaId = queue[20].mediaId,
                        queueSource = PlaybackQueueSource.library(),
                        queueItems = queue,
                        queueIndex = 20,
                    ),
                    onSelect = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithTag("now-playing-queue-item-20")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now-playing-queue-item-20").assertIsDisplayed()
    }

    @Test
    fun libraryAndFilteredPlaybackExposeDifferentQueueSources() {
        val first = song(101L, "可搜索第一首")
        val second = song(102L, "普通第二首")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()

        compose.onNodeWithTag("library-song-${second.id}").performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithTag("now-playing-source").assertTextEquals("全部歌曲")
        compose.onNodeWithContentDescription("收起播放器").performClick()

        compose.onNodeWithText("搜索歌曲、歌手或专辑").performTextInput("可搜索")
        compose.onNodeWithTag("library-song-${first.id}").performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithTag("now-playing-source").assertTextEquals("搜索结果")
    }

    @Test
    fun playbackSettingsTogglePublishesTheAudioMixingChoice() {
        val first = song(101L, "设置测试歌曲")
        val second = song(102L, "队列歌曲")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()
        compose.onNodeWithTag("library-song-${first.id}").performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.onNodeWithTag("mini-player").performClick()

        compose.onNodeWithContentDescription("播放设置：顺序播放").performClick()
        compose.onNodeWithText("与其他应用同时播放").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.audioMixingEnabled
        }

        compose.onNodeWithContentDescription("播放设置：顺序播放，与其他应用同时播放")
            .assertIsDisplayed()
    }

    @Test
    fun refreshFailureKeepsSongsVisibleAndOffersAWorkingRetry() {
        val first = song(101L, "保留的歌曲")
        val second = song(102L, "另一首歌")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()

        compose.runOnIdle {
            fixture.music.failure = IllegalStateException("temporary MediaStore failure")
            fixture.viewModel.refreshSongs()
        }
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.viewModel.libraryError.value != null && !fixture.viewModel.isLoading.value
        }
        compose.onNodeWithTag("library-refresh-error").assertIsDisplayed()
        compose.onNodeWithTag("library-song-${first.id}").assertIsDisplayed()

        compose.runOnIdle { fixture.music.failure = null }
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.viewModel.libraryError.value == null && !fixture.viewModel.isLoading.value
        }
        compose.onAllNodesWithTag("library-refresh-error").assertCountEquals(0)
        compose.onNodeWithTag("library-song-${first.id}").assertIsDisplayed()
    }

    private fun setContentAndLoad() {
        compose.setContent {
            MelodyTheme {
                MelodyApp(
                    viewModel = fixture.viewModel,
                    hasAudioPermission = true,
                    onRequestPermission = {},
                )
            }
        }
        compose.runOnIdle { fixture.viewModel.refreshSongs() }
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.viewModel.allSongs.value.size == fixture.expectedSongCount &&
                fixture.viewModel.playlists.value.singleOrNull()?.id == PLAYLIST_ID
        }
    }

    private fun assertQueueItem(index: Int, title: String) {
        compose.onNode(
            hasTestTag("now-playing-queue-item-$index") and
                hasAnyDescendant(hasText(title)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private inner class Fixture(
        librarySongs: List<Song>,
        playlistSongIds: List<Long>,
    ) {
        val expectedSongCount = librarySongs.size
        val playlists = FakePlaylistStore(playlistSongIds)
        val player = FakePlaybackController()
        val music = FakeMusicLibrary(librarySongs)
        private val store = ViewModelStore()
        private val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val viewModel: MainViewModel = ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
                    application = application,
                    musicRepository = music,
                    playlistRepository = playlists,
                    lyricsRepository = EmptyLyricsStore,
                    player = player,
                ) as T
            },
        )[MainViewModel::class.java]

        fun clear() = store.clear()
    }

    private class FakeMusicLibrary(private val songs: List<Song>) : MusicLibrary {
        var failure: Exception? = null

        override suspend fun loadSongs(): List<Song> {
            failure?.let { throw it }
            return songs
        }
    }

    private class FakePlaylistStore(songIds: List<Long>) : PlaylistStore {
        override val playlists = MutableStateFlow(
            listOf(
                PlaylistSummary(
                    id = PLAYLIST_ID,
                    name = PLAYLIST_NAME,
                    createdAt = 1L,
                    songCount = songIds.size,
                )
            )
        )
        private val playlistSongIds = MutableStateFlow(songIds)

        override suspend fun create(name: String): Long = PLAYLIST_ID
        override suspend fun rename(playlistId: Long, name: String) = Unit
        override suspend fun delete(playlistId: Long) = Unit
        override suspend fun addSong(playlistId: Long, songId: Long): Boolean = false
        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit
        override fun observeSongIds(playlistId: Long): Flow<List<Long>> = playlistSongIds
        override suspend fun getAllSongIds(): List<Long> = playlistSongIds.value
        override suspend fun remapSongIds(remaps: Map<Long, Long>) = Unit
    }

    private class FakePlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> = mutableState
        private var activeSongs = emptyList<Song>()
        private var activeSource: PlaybackQueueSource? = null
        var queueReplacementCount = 0
            private set
        val selectedQueueIndices = mutableListOf<Int>()

        override fun playQueue(
            songs: List<Song>,
            startIndex: Int,
            source: PlaybackQueueSource,
        ) {
            if (songs.isEmpty()) return
            activeSongs = songs.toList()
            activeSource = source
            queueReplacementCount++
            publish(startIndex.coerceIn(activeSongs.indices), isPlaying = true)
        }

        override fun seekToQueueItem(index: Int) {
            if (index !in activeSongs.indices) return
            selectedQueueIndices += index
            publish(index, isPlaying = mutableState.value.isPlaying)
        }

        override fun togglePlayPause() {
            mutableState.value = mutableState.value.copy(
                isPlaying = !mutableState.value.isPlaying,
            )
        }

        override fun stop() {
            mutableState.value = PlaybackUiState(
                playbackMode = mutableState.value.playbackMode,
                audioMixingEnabled = mutableState.value.audioMixingEnabled,
            )
            activeSongs = emptyList()
            activeSource = null
        }

        override fun seekTo(positionMs: Long) {
            mutableState.value = mutableState.value.copy(positionMs = positionMs)
        }

        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit

        override fun setPlaybackMode(mode: PlaybackMode) {
            mutableState.value = mutableState.value.copy(playbackMode = mode)
        }

        override fun setAudioMixingEnabled(enabled: Boolean) {
            mutableState.value = mutableState.value.copy(audioMixingEnabled = enabled)
        }

        override fun release() = Unit

        fun pauseForTest() {
            mutableState.value = mutableState.value.copy(isPlaying = false)
        }

        private fun publish(index: Int, isPlaying: Boolean) {
            val current = activeSongs[index]
            mutableState.value = mutableState.value.copy(
                mediaId = current.id,
                title = current.title,
                artist = current.artist,
                album = current.album,
                artworkUri = current.albumArtUri,
                isPlaying = isPlaying,
                positionMs = 0L,
                durationMs = current.durationMs,
                queueSource = activeSource,
                queueItems = activeSongs.map { song ->
                    PlaybackQueueItem(
                        mediaId = song.id,
                        title = song.title,
                        artist = song.artist,
                        artworkUri = song.albumArtUri,
                    )
                },
                queueIndex = index,
            )
        }
    }

    private data object EmptyLyricsStore : LyricsStore {
        override suspend fun load(songId: Long) = null
        override suspend fun import(songId: Long, uri: Uri) =
            throw UnsupportedOperationException("not used by this test")
        override suspend fun delete(songId: Long) = Unit
        override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
    }

    private companion object {
        const val PLAYLIST_ID = 42L
        const val PLAYLIST_NAME = "QA上下文歌单"

        fun song(id: Long, title: String) = Song(
            id = id,
            title = title,
            artist = "测试歌手 $id",
            album = "测试专辑",
            albumId = 1L,
            durationMs = 60_000L,
            trackNumber = id.toInt(),
            dateAddedSeconds = id,
            contentUri = Uri.parse("content://songs/$id"),
            albumArtUri = null,
        )
    }
}
