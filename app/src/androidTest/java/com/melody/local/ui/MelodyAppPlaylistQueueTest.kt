package com.melody.local.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

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

        openPlaylist()
        playlistSong(first.id).performClick()
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
    fun playlistArtistSortingChangesVisibleAndNewPlaybackOrderWithoutReplacingTheActiveQueue() {
        val first = song(101L, "先加入的歌").copy(artist = "Zulu")
        val second = song(102L, "后加入的歌").copy(artist = "Alpha")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
        )
        setContentAndLoad()
        openPlaylist()
        playlistSong(first.id).performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }

        scrollTo(hasText("歌手")).performClick()
        waitForPlaylistSongs(listOf(second.id, first.id))
        compose.onNodeWithText("歌手").assertIsSelected()
        // Reveal both rows before measuring their positions in the same viewport.
        playlistSong(first.id)
        playlistSong(second.id)
        compose.onNodeWithTag("playlist-song-${first.id}").assertIsDisplayed()
        val firstBounds = compose.onNodeWithTag("playlist-song-${first.id}").fetchSemanticsNode().boundsInRoot
        val secondBounds = compose.onNodeWithTag("playlist-song-${second.id}").fetchSemanticsNode().boundsInRoot
        assertTrue(secondBounds.top < firstBounds.top)
        compose.runOnIdle {
            assertEquals(1, fixture.player.queueReplacementCount)
            assertEquals(listOf(first.id, second.id), fixture.player.state.value.queueItems.map { it.mediaId })
            assertFalse(fixture.player.state.value.isPlaying)
        }

        playlistSong(second.id).performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.player.queueReplacementCount)
            assertEquals(listOf(second.id, first.id), fixture.player.state.value.queueItems.map { it.mediaId })
            assertEquals(second.id, fixture.player.state.value.mediaId)
        }
    }

    @Test
    fun launchOpensTheRememberedPlaylistWithItsSavedSortAndBackShowsThePlaylistList() {
        val first = song(101L, "先加入的歌").copy(artist = "Zulu")
        val second = song(102L, "后加入的歌").copy(artist = "Alpha")
        fixture = Fixture(
            librarySongs = listOf(first, second),
            playlistSongIds = listOf(first.id, second.id),
            rememberedPlaylistId = PLAYLIST_ID,
            restoredSort = PlaylistSort.ARTIST,
        )
        setContentAndLoad()
        waitForPlaylistSongs(listOf(second.id, first.id))
        compose.onNodeWithText(PLAYLIST_NAME).assertIsDisplayed()
        compose.onNodeWithText("歌手").assertIsSelected()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的歌单").assertIsDisplayed()
        compose.onNodeWithText(PLAYLIST_NAME).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(PLAYLIST_ID, fixture.preferences.lastOpenedPlaylistId)
            assertEquals(0, fixture.player.queueReplacementCount)
        }
    }

    @Test
    fun switchingToASlowPlaylistDoesNotExposeOrPlayThePreviousPlaylistsSongs() {
        val first = song(101L, "仅属于旧歌单")
        val second = song(102L, "仅属于新歌单")
        fixture = Fixture(listOf(first, second), listOf(first.id))
        setContentAndLoad()
        openPlaylist()

        val loaded = CompletableDeferred<Unit>()
        val secondPlaylistId = PLAYLIST_ID + 1L
        compose.runOnIdle {
            fixture.playlists.songFlows[secondPlaylistId] = flow {
                loaded.await()
                emit(listOf(second.id))
            }
            fixture.playlists.playlists.value += PlaylistSummary(
                id = secondPlaylistId,
                name = "慢加载歌单",
                createdAt = 2L,
                songCount = 1,
            )
            fixture.viewModel.selectPlaylist(secondPlaylistId)
        }
        compose.onNodeWithText("慢加载歌单").assertIsDisplayed()
        compose.onAllNodesWithTag("playlist-song-${first.id}").assertCountEquals(0)
        scrollTo(hasText("播放")).assertIsNotEnabled()
        scrollTo(hasText("歌手")).performClick()
        compose.onAllNodesWithTag("playlist-song-${first.id}").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, fixture.player.queueReplacementCount) }

        loaded.complete(Unit)
        waitForPlaylistSongs(listOf(second.id), playlistId = secondPlaylistId)
        playlistSong(second.id).performClick()
        compose.runOnIdle {
            assertEquals(listOf(second.id), fixture.player.state.value.queueItems.map { it.mediaId })
            assertEquals(
                PlaybackQueueSource.playlist(secondPlaylistId, "慢加载歌单"),
                fixture.player.state.value.queueSource,
            )
        }
    }

    @Test
    fun restoringASavedLibraryTabWithANewViewModelKeepsPlaylistNavigationConsistent() {
        val first = song(101L, "恢复测试歌曲")
        val preferencesName = "playlist_saved_tab_${UUID.randomUUID()}"
        fixture = Fixture(listOf(first), listOf(first.id), preferencesName = preferencesName)
        val activeViewModel = mutableStateOf(fixture.viewModel)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MelodyTheme {
                MelodyApp(activeViewModel.value, hasAudioPermission = true, onRequestPermission = {})
            }
        }
        compose.runOnIdle { fixture.viewModel.refreshSongs() }
        waitForLibrarySongs(setOf(first.id))
        openPlaylist()
        compose.onNodeWithText("歌曲").performClick()
        compose.onNodeWithText("我的音乐").assertIsDisplayed()

        compose.runOnIdle {
            fixture.clear(clearPreferences = false)
            fixture = Fixture(listOf(first), listOf(first.id), preferencesName = preferencesName)
            activeViewModel.value = fixture.viewModel
            fixture.viewModel.refreshSongs()
        }
        restoration.emulateSavedInstanceStateRestore()
        waitForPlaylistSongs(listOf(first.id))
        compose.onNodeWithText(PLAYLIST_NAME).assertIsDisplayed()
        compose.onNodeWithText("歌单").assertIsSelected()
        compose.onNodeWithText("歌曲").assertIsNotSelected()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的歌单").assertIsDisplayed()
    }

    @Test
    fun restoredPlaylistShowsLoadingAndAWorkingRetryInsteadOfAFakeEmptyState() {
        val first = song(101L, "重试后恢复的歌")
        val loaded = CompletableDeferred<List<Song>>()
        fixture = Fixture(listOf(first), listOf(first.id), rememberedPlaylistId = PLAYLIST_ID)
        fixture.music.loadGate = loaded
        compose.setContent {
            MelodyTheme {
                MelodyApp(fixture.viewModel, hasAudioPermission = true, onRequestPermission = {})
            }
        }
        try {
            compose.runOnIdle { fixture.viewModel.refreshSongs() }
            compose.waitUntil(timeoutMillis = 5_000L) { fixture.viewModel.isLoading.value }
            scrollTo(hasText("正在加载歌单…")).assertIsDisplayed()
            compose.onAllNodes(hasText("歌单还是空的")).assertCountEquals(0)

            loaded.completeExceptionally(IOException("MediaStore unavailable"))
            compose.waitUntil(timeoutMillis = 5_000L) { fixture.viewModel.libraryError.value != null }
            scrollTo(hasText("曲库加载失败")).assertIsDisplayed()
            compose.onAllNodes(hasText("歌单还是空的")).assertCountEquals(0)
            compose.runOnIdle { fixture.music.loadGate = null }
            scrollTo(hasText("重试")).performClick()
            waitForPlaylistSongs(listOf(first.id))
            playlistSong(first.id).assertIsDisplayed()

            compose.runOnIdle {
                fixture.music.failure = IOException("refresh failure")
                fixture.viewModel.refreshSongs()
            }
            compose.waitUntil(timeoutMillis = 5_000L) { fixture.viewModel.libraryError.value != null }
            scrollTo(hasTestTag("playlist-refresh-error")).assertIsDisplayed()
            playlistSong(first.id).assertIsDisplayed()
            compose.runOnIdle { fixture.music.failure = null }
            scrollTo(hasText("重试")).performClick()
            compose.waitUntil(timeoutMillis = 5_000L) { fixture.viewModel.libraryError.value == null }
            compose.onAllNodesWithTag("playlist-refresh-error").assertCountEquals(0)
        } finally {
            loaded.complete(emptyList())
        }
    }

    @Test
    fun tappingTheCurrentSongAfterSortingStartsTheNewOrderBeforeOpeningThePlayer() {
        val first = song(101L, "当前歌曲").copy(artist = "Zulu")
        val second = song(102L, "排序后第一首").copy(artist = "Alpha")
        fixture = Fixture(listOf(first, second), listOf(first.id, second.id))
        setContentAndLoad()
        openPlaylist()
        playlistSong(first.id).performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }
        scrollTo(hasText("歌手")).performClick()
        waitForPlaylistSongs(listOf(second.id, first.id))
        playlistSong(first.id).performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.player.queueReplacementCount)
            assertEquals(listOf(second.id, first.id), fixture.player.state.value.queueItems.map { it.mediaId })
            assertEquals(1, fixture.player.state.value.queueIndex)
            fixture.player.pauseForTest()
        }
        playlistSong(first.id).performClick()
        compose.onNodeWithTag("now-playing-source").assertTextEquals("来自歌单：$PLAYLIST_NAME")
        compose.runOnIdle { assertEquals(2, fixture.player.queueReplacementCount) }
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

        openPlaylist()
        playlistSong(first.id).performClick()
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

        librarySong(first.id).performClick()
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

        searchLibrary("独有", setOf(first.id))
        librarySong(first.id).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.player.state.value.queueSource == PlaybackQueueSource.library("搜索结果") &&
                fixture.player.queueReplacementCount == 2
        }
        compose.runOnIdle {
            assertEquals(listOf(first.id), fixture.player.state.value.queueItems.map { it.mediaId })
        }

        searchLibrary("共同", setOf(first.id, second.id))
        librarySong(first.id).performClick()
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

        librarySong(second.id).performClick()
        compose.runOnIdle { fixture.player.pauseForTest() }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithTag("now-playing-source").assertTextEquals("全部歌曲")
        compose.onNodeWithContentDescription("收起播放器").performClick()

        searchLibrary("可搜索", setOf(first.id))
        librarySong(first.id).performClick()
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
        librarySong(first.id).performClick()
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
        scrollTo(hasTestTag("library-refresh-error")).assertIsDisplayed()
        librarySong(first.id).assertIsDisplayed()

        compose.runOnIdle { fixture.music.failure = null }
        scrollTo(hasText("重试")).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            fixture.viewModel.libraryError.value == null && !fixture.viewModel.isLoading.value
        }
        compose.onAllNodesWithTag("library-refresh-error").assertCountEquals(0)
        librarySong(first.id).assertIsDisplayed()
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
        waitForLibrarySongs(fixture.expectedLibrarySongIds)
    }

    private fun openPlaylist() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            fixture.viewModel.playlists.value.any { it.id == PLAYLIST_ID }
        }
        compose.onNodeWithText("歌单").performClick()
        scrollTo(hasText(PLAYLIST_NAME)).performClick()
        waitForPlaylistSongs(fixture.expectedPlaylistSongIds)
    }

    private fun waitForPlaylistSongs(
        expectedIds: List<Long>,
        playlistId: Long = PLAYLIST_ID,
    ) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            val contents = fixture.viewModel.selectedPlaylistContents.value
            contents.playlistId == playlistId && !contents.isLoading &&
                contents.sort == fixture.viewModel.playlistSort.value &&
                contents.songs.map(Song::id) == expectedIds
        }
    }

    private fun playlistSong(id: Long): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis = 10_000L) {
            val contents = fixture.viewModel.selectedPlaylistContents.value
            contents.playlistId == fixture.viewModel.selectedPlaylistId.value && !contents.isLoading &&
                contents.sort == fixture.viewModel.playlistSort.value && contents.songs.any { it.id == id }
        }
        return scrollTo(hasTestTag("playlist-song-$id"))
    }

    private fun waitForLibrarySongs(expectedIds: Set<Long>) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            !fixture.viewModel.isLoading.value &&
                fixture.viewModel.visibleSongs.value.map(Song::id).toSet() == expectedIds
        }
    }

    private fun librarySong(id: Long): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis = 10_000L) {
            fixture.viewModel.visibleSongs.value.any { it.id == id }
        }
        return scrollTo(hasTestTag("library-song-$id"))
    }

    private fun searchLibrary(query: String, expectedIds: Set<Long>) {
        val search = scrollTo(hasTestTag("library-search"))
        search.performTextClearance()
        search.performTextInput(query)
        closeSoftKeyboard()
        waitForLibrarySongs(expectedIds)
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val verticalList = hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        compose.onNode(verticalList).performScrollToNode(matcher)
        return compose.onNode(matcher).assertIsDisplayed()
    }

    private fun assertQueueItem(index: Int, title: String) {
        scrollTo(hasTestTag("now-playing-queue-item-$index"))
        compose.onNode(
            hasTestTag("now-playing-queue-item-$index") and
                hasAnyDescendant(hasText(title)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private inner class Fixture(
        librarySongs: List<Song>,
        playlistSongIds: List<Long>,
        rememberedPlaylistId: Long? = null,
        restoredSort: PlaylistSort? = null,
        private val preferencesName: String = "playlist_ui_${UUID.randomUUID()}",
    ) {
        val preferences = PlaylistPreferences(application, preferencesName).apply {
            if (rememberedPlaylistId != null) lastOpenedPlaylistId = rememberedPlaylistId
            if (restoredSort != null) sort = restoredSort
        }
        val expectedSongCount = librarySongs.size
        val expectedLibrarySongIds = librarySongs.map(Song::id).toSet()
        val expectedPlaylistSongIds = playlistSongIds.toList()
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
                    playlistPreferences = preferences,
                ) as T
            },
        )[MainViewModel::class.java]

        fun clear(clearPreferences: Boolean = true) {
            store.clear()
            if (clearPreferences) application.deleteSharedPreferences(preferencesName)
        }
    }

    private class FakeMusicLibrary(private val songs: List<Song>) : MusicLibrary {
        var failure: Exception? = null
        var loadGate: CompletableDeferred<List<Song>>? = null

        override suspend fun loadSongs(): List<Song> {
            failure?.let { throw it }
            return loadGate?.await() ?: songs
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
        val songFlows = mutableMapOf<Long, Flow<List<Long>>>(PLAYLIST_ID to playlistSongIds)

        override suspend fun create(name: String): Long = PLAYLIST_ID
        override suspend fun rename(playlistId: Long, name: String) = Unit
        override suspend fun delete(playlistId: Long) = Unit
        override suspend fun addSong(playlistId: Long, songId: Long): Boolean = false
        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit
        override fun observeSongIds(playlistId: Long): Flow<List<Long>> =
            songFlows[playlistId] ?: flow { emit(emptyList()) }
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
