package com.melody.local.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melody.local.data.Song
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.SongOverrideEntity
import com.melody.local.lyrics.LyricsRepository
import com.melody.local.systemlyrics.SystemLyricsSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class PlayerConnectionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var audioFile: File
    private lateinit var connection: PlayerConnection

    @Before
    fun setUp() {
        audioFile = File(context.cacheDir, "player-connection-test.wav")
        writeSilentWave(audioFile, durationSeconds = 20)
        onMain { connection = PlayerConnection(context) }
    }

    @After
    fun tearDown() {
        onMain {
            connection.stop()
            connection.release()
        }
        audioFile.delete()
    }

    @Test
    fun queueMetadataModeAndStopFlowThroughTheService() {
        val songs = testSongs()

        onMain {
            connection.playQueue(emptyList(), startIndex = 0)
            connection.setPlaybackMode(PlaybackMode.SEQUENTIAL)
            connection.playQueue(songs, startIndex = 99)
        }
        waitUntil {
            connection.state.value.mediaId == 2L && connection.state.value.title == "Second"
        }

        assertEquals("Second", connection.state.value.title)
        assertEquals("Artist 2", connection.state.value.artist)
        assertEquals("Album 2", connection.state.value.album)
        assertEquals("content://art/2", connection.state.value.artworkUri.toString())

        onMain { connection.setPlaybackMode(PlaybackMode.REVERSE) }
        waitUntil { connection.state.value.playbackMode == PlaybackMode.REVERSE }
        assertEquals(2L, connection.state.value.mediaId)

        onMain { connection.seekToNext() }
        waitUntil { connection.state.value.mediaId == 1L }

        onMain { connection.stop() }
        waitUntil { connection.state.value.mediaId == null }
        assertEquals(0L, connection.state.value.durationMs)
    }

    @Test
    fun coldQueueKeepsTheModeAlreadyOwnedByTheService() {
        val songs = testSongs()
        onMain { connection.setPlaybackMode(PlaybackMode.REVERSE) }
        waitUntil { connection.state.value.playbackMode == PlaybackMode.REVERSE }
        waitForServiceMode(PlaybackMode.REVERSE)

        onMain {
            connection.release()
            connection = PlayerConnection(context)
            connection.playQueue(songs, startIndex = 0)
        }
        waitUntil {
            connection.state.value.mediaId == 1L &&
                connection.state.value.title == "First" &&
                connection.state.value.playbackMode == PlaybackMode.REVERSE
        }

        onMain { connection.seekToNext() }
        waitUntil { connection.state.value.mediaId == 2L }
    }

    @Test
    fun seekPreviousAndNextRespectPlaybackBoundaries() {
        val songs = testSongs()
        onMain {
            connection.setPlaybackMode(PlaybackMode.SEQUENTIAL)
            connection.playQueue(songs, startIndex = 1)
        }
        waitUntil { connection.state.value.mediaId == 2L && connection.state.value.durationMs > 0L }

        onMain { connection.seekTo(8_000L) }
        waitUntil { connection.state.value.positionMs >= 7_500L }
        onMain { connection.seekToPrevious() }
        waitUntil { connection.state.value.mediaId == 2L && connection.state.value.positionMs < 1_000L }

        onMain { connection.seekToPrevious() }
        waitUntil { connection.state.value.mediaId == 1L }
        onMain { connection.seekToNext() }
        waitUntil { connection.state.value.mediaId == 2L }

        onMain { connection.seekTo(Long.MAX_VALUE) }
        waitUntil {
            val state = connection.state.value
            state.durationMs > 0L && state.positionMs <= state.durationMs
        }
        assertTrue(connection.state.value.positionMs <= connection.state.value.durationMs)
    }

    @Test
    fun clippedSongUsesRelativeProgressAndAutomaticallyAdvancesAtTheSavedEnd() {
        val trimmed = song(31L, "Trimmed", "Artist", "Album", null).copy(
            playbackStartMs = 5_000L,
            playbackEndMs = 7_000L,
        )
        val next = song(32L, "Next", "Artist", "Album", null)

        onMain {
            connection.setPlaybackMode(PlaybackMode.SEQUENTIAL)
            connection.playQueue(listOf(trimmed, next), 0)
        }
        waitUntil {
            val state = connection.state.value
            state.mediaId == trimmed.id && state.durationMs in 1_900L..2_100L
        }

        assertEquals(5_000L, connection.state.value.playbackStartMs)
        assertTrue(connection.state.value.sourcePositionMs >= 5_000L)
        assertTrue(connection.state.value.positionMs < connection.state.value.sourcePositionMs)
        waitUntil(timeoutMs = 5_000L) { connection.state.value.mediaId == next.id }
    }

    @Test
    fun trimmingQueuedSongPreservesPausedCurrentPositionAndAppliesRealPlaybackBounds() = runBlocking {
        val dao = PlaylistDatabase.getInstance(context).songStateDao()
        val currentId = 705L
        val trimmedId = 706L
        val followingId = 707L
        val ids = listOf(currentId, trimmedId, followingId)
        val previousOverrides = ids.associateWith { dao.getOverride(it) }
        try {
            ids.forEach { dao.deleteOverride(it) }
            val songs = listOf(
                song(currentId, "Paused current", "Artist", "Album", null),
                song(trimmedId, "Originally complete", "Artist", "Album", null),
                song(followingId, "After trimmed song", "Artist", "Album", null),
            )
            onMain {
                connection.setPlaybackMode(PlaybackMode.SEQUENTIAL)
                connection.playQueue(songs, 0)
            }
            waitUntil {
                connection.state.value.mediaId == currentId && connection.state.value.isPlaying
            }
            onMain { connection.togglePlayPause() }
            waitUntil { !connection.state.value.isPlaying }
            onMain { connection.seekTo(3_000L) }
            waitUntil { connection.state.value.positionMs in 2_900L..3_100L }
            val pausedPositionMs = connection.state.value.positionMs

            dao.upsertOverride(
                SongOverrideEntity(
                    songId = trimmedId,
                    title = "Queued trim committed 2-6 seconds",
                    artist = "Artist",
                    album = "Album",
                    artworkPath = null,
                    playbackStartMs = 2_000L,
                    playbackEndMs = 6_000L,
                )
            )
            // Wait for the service's committed queue update before selecting B. Otherwise the
            // test can accidentally exercise the already-covered current-item clipping path.
            waitUntil {
                connection.state.value.queueItems.any {
                    it.mediaId == trimmedId && it.title == "Queued trim committed 2-6 seconds"
                }
            }
            assertEquals(currentId, connection.state.value.mediaId)
            assertTrue(!connection.state.value.isPlaying)
            assertTrue(
                kotlin.math.abs(connection.state.value.positionMs - pausedPositionMs) <= 100L
            )

            onMain { connection.seekToQueueItem(1) }
            waitUntil {
                val state = connection.state.value
                state.mediaId == trimmedId && state.durationMs > 0L
            }
            assertEquals(2_000L, connection.state.value.playbackStartMs)
            assertTrue(
                "Queued clipping must change the actual Media3 duration",
                connection.state.value.durationMs in 3_900L..4_100L,
            )
            assertTrue(!connection.state.value.isPlaying)
            onMain { connection.togglePlayPause() }
            waitUntil { connection.state.value.isPlaying }
            waitUntil(timeoutMs = 6_000L) { connection.state.value.mediaId == followingId }
        } finally {
            ids.forEach { id ->
                val previous = previousOverrides[id]
                if (previous == null) dao.deleteOverride(id) else dao.upsertOverride(previous)
            }
        }
    }

    @Test
    fun editingCurrentSongRangeRestartsAtTrimStartWithoutReplacingTheQueue() = runBlocking {
        val dao = PlaylistDatabase.getInstance(context).songStateDao()
        val currentId = 703L
        val nextId = 704L
        val previousCurrent = dao.getOverride(currentId)
        val previousNext = dao.getOverride(nextId)
        try {
            dao.deleteOverride(currentId)
            dao.deleteOverride(nextId)
            val songs = listOf(
                song(currentId, "Original", "Artist", "Album", null),
                song(nextId, "Next", "Artist", "Album", null),
            )
            onMain {
                connection.setPlaybackMode(PlaybackMode.REVERSE)
                connection.playQueue(songs, 0)
            }
            waitUntil { connection.state.value.mediaId == currentId && connection.state.value.isPlaying }
            onMain { connection.seekTo(3_000L) }
            waitUntil { connection.state.value.positionMs >= 2_500L }

            dao.upsertOverride(
                SongOverrideEntity(
                    songId = currentId,
                    title = "Original",
                    artist = "Artist",
                    album = "Album",
                    artworkPath = null,
                    playbackStartMs = 4_000L,
                    playbackEndMs = 9_000L,
                )
            )
            waitUntil {
                val state = connection.state.value
                state.playbackStartMs == 4_000L &&
                    state.durationMs in 4_900L..5_100L &&
                    state.positionMs < 1_500L &&
                    state.isPlaying
            }

            assertEquals(currentId, connection.state.value.mediaId)
            assertEquals(listOf(currentId, nextId), connection.state.value.queueItems.map { it.mediaId })
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
            assertTrue(connection.state.value.isPlaying)

            dao.upsertOverride(
                SongOverrideEntity(
                    songId = nextId,
                    title = "Next",
                    artist = "Artist",
                    album = "Album",
                    artworkPath = null,
                    playbackStartMs = 2_000L,
                    playbackEndMs = 6_000L,
                )
            )
            onMain { connection.seekToNext() }
            waitUntil {
                val state = connection.state.value
                state.mediaId == nextId &&
                    state.playbackStartMs == 2_000L &&
                    state.durationMs in 3_900L..4_100L
            }
        } finally {
            if (previousCurrent == null) dao.deleteOverride(currentId)
            else dao.upsertOverride(previousCurrent)
            if (previousNext == null) dao.deleteOverride(nextId)
            else dao.upsertOverride(previousNext)
        }
    }

    @Test
    fun systemLyricMetadataIsPublishedAndRestoresPreviousQueueItems() = runBlocking {
        val repository = LyricsRepository(context)
        val firstId = 81L
        val secondId = 82L
        repository.delete(firstId)
        repository.delete(secondId)
        repository.save(firstId, "[00:00.00]First lyric")
        repository.save(secondId, "[00:00.00]Second lyric")
        SystemLyricsSettings(context).notificationLyricsEnabled = true
        val songs = listOf(
            song(firstId, "First title", "Artist 1", "Album 1", Uri.parse("content://art/81")),
            song(secondId, "Second title", "Artist 2", "Album 2", Uri.parse("content://art/82")),
        )
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = future.get(10, TimeUnit.SECONDS)
        try {
            onMain { connection.playQueue(songs, 0) }
            waitUntil { connection.state.value.mediaId == firstId }
            waitUntil {
                readController(observer) {
                    currentMediaItem?.mediaMetadata?.displayTitle?.toString()
                } == "First lyric"
            }

            onMain { connection.seekToNext() }
            waitUntil { connection.state.value.mediaId == secondId }
            waitUntil {
                readController(observer) {
                    currentMediaItem?.mediaMetadata?.displayTitle?.toString()
                } == "Second lyric"
            }
            assertNotEquals(
                "First lyric",
                readController(observer) {
                    getMediaItemAt(0).mediaMetadata.displayTitle?.toString()
                },
            )
        } finally {
            onMain { MediaController.releaseFuture(future) }
            repository.delete(firstId)
            repository.delete(secondId)
        }
    }

    @Test
    fun editedMetadataRefreshesCurrentAndQueuedSongsWithoutRestartingPlayback() = runBlocking {
        val dao = PlaylistDatabase.getInstance(context).songStateDao()
        val currentId = 701L
        val nextId = 702L
        val previousCurrent = dao.getOverride(currentId)
        val previousNext = dao.getOverride(nextId)
        val lyrics = LyricsRepository(context)
        val settings = SystemLyricsSettings(context)
        val previousSetting = settings.notificationLyricsEnabled
        settings.notificationLyricsEnabled = true
        lyrics.save(currentId, "[00:00.00]Visible lyric")
        val future = MediaController.Builder(
            context, SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = future.get(10, TimeUnit.SECONDS)
        try {
            val songs = listOf(
                song(currentId, "Original", "Artist", "Album", Uri.parse("content://art/original")),
                song(nextId, "Next", "Artist", "Album", null),
            )
            onMain {
                connection.setPlaybackMode(PlaybackMode.REVERSE)
                connection.playQueue(songs, 0)
            }
            waitUntil { connection.state.value.mediaId == currentId && connection.state.value.isPlaying }
            onMain { connection.seekTo(3000) }
            waitUntil { connection.state.value.positionMs >= 3000 }
            val before = readController(observer) { currentPosition }
            dao.upsertOverride(SongOverrideEntity(nextId, "Edited next", "Artist 2", "Album 2", null))
            waitUntil {
                connection.state.value.title == "Original" &&
                    connection.state.value.queueItems.map { it.title } ==
                    listOf("Original", "Edited next") &&
                    readController(observer) { getMediaItemAt(1).mediaMetadata.title.toString() } == "Edited next"
            }

            dao.upsertOverride(SongOverrideEntity(currentId, "Edited", "New artist", "New album", null))
            waitUntil {
                connection.state.value.title == "Edited" &&
                    connection.state.value.queueItems.map { it.title } ==
                    listOf("Edited", "Edited next") &&
                    readController(observer) { getMediaItemAt(1).mediaMetadata.title.toString() } == "Edited next"
            }
            assertEquals(currentId, connection.state.value.mediaId)
            assertEquals("New artist", connection.state.value.artist)
            assertEquals("New album", connection.state.value.album)
            assertEquals("content://art/original", connection.state.value.artworkUri.toString())
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
            assertTrue(connection.state.value.isPlaying)
            assertTrue(readController(observer) { currentPosition } >= before)
            assertEquals(2, readController(observer) { mediaItemCount })
            assertEquals(listOf(currentId, nextId), connection.state.value.queueItems.map { it.mediaId })
            waitUntil {
                readController(observer) { currentMediaItem!!.mediaMetadata.subtitle.toString() } ==
                    "Edited · New artist"
            }
            assertEquals("Visible lyric", readController(observer) { currentMediaItem!!.mediaMetadata.displayTitle.toString() })
            onMain { connection.togglePlayPause() }
            waitUntil { !connection.state.value.isPlaying && !readController(observer) { playWhenReady } }
            val pausedPosition = readController(observer) { currentPosition }
            dao.upsertOverride(SongOverrideEntity(currentId, "Edited paused", "New artist", "New album", null))
            waitUntil { connection.state.value.title == "Edited paused" }
            val positionAfterMetadataUpdate = readController(observer) { currentPosition }
            assertTrue(
                "paused position moved unexpectedly: $pausedPosition -> $positionAfterMetadataUpdate",
                positionAfterMetadataUpdate in pausedPosition..(pausedPosition + 250L),
            )
            assertTrue(!connection.state.value.isPlaying)
        } finally {
            onMain { MediaController.releaseFuture(future) }
            lyrics.delete(currentId)
            settings.notificationLyricsEnabled = previousSetting
            if (previousCurrent == null) dao.deleteOverride(currentId) else dao.upsertOverride(previousCurrent)
            if (previousNext == null) dao.deleteOverride(nextId) else dao.upsertOverride(previousNext)
        }
    }

    @Test
    fun resumesAfterCompetingPlaybackReleasesPermanentAudioFocus() {
        setAudioMixing(false)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val competingFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        val competingPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(audioFile.absolutePath)
            isLooping = true
            prepare()
        }

        try {
            onMain { connection.playQueue(testSongs().take(1), startIndex = 0) }
            waitUntil { connection.state.value.isPlaying }

            assertEquals(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(competingFocusRequest),
            )
            competingPlayer.start()
            waitUntil { !connection.state.value.isPlaying }
            val interruptedPositionMs = connection.state.value.positionMs

            competingPlayer.stop()
            audioManager.abandonAudioFocusRequest(competingFocusRequest)

            waitUntil(timeoutMs = 5_000L) { connection.state.value.isPlaying }
            assertEquals(1L, connection.state.value.mediaId)
            assertTrue(connection.state.value.positionMs >= interruptedPositionMs)
        } finally {
            runCatching { competingPlayer.release() }
            audioManager.abandonAudioFocusRequest(competingFocusRequest)
        }
    }

    @Test
    fun audioMixingKeepsThisPlayerAndTheCompetingSourcePlaying() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val competingFocusChanges = CopyOnWriteArrayList<Int>()
        val competingFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(
                { change -> competingFocusChanges += change },
                Handler(Looper.getMainLooper()),
            )
            .build()
        val competingPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(audioFile.absolutePath)
            isLooping = true
            prepare()
        }

        try {
            assertEquals(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(competingFocusRequest),
            )
            competingPlayer.start()
            assertTrue(competingPlayer.isPlaying)

            setAudioMixing(true)
            onMain { connection.playQueue(testSongs().take(1), startIndex = 0) }
            waitUntil {
                connection.state.value.audioMixingEnabled &&
                    connection.state.value.isPlaying
            }
            waitUntil { audioManager.activePlaybackConfigurations.size >= 2 }

            assertTrue(competingPlayer.isPlaying)
            assertTrue(connection.state.value.isPlaying)
            assertTrue(
                "mixing playback unexpectedly took audio focus: $competingFocusChanges",
                competingFocusChanges.none { it < 0 },
            )
        } finally {
            onMain { connection.stop() }
            runCatching { competingPlayer.stop() }
            runCatching { competingPlayer.release() }
            audioManager.abandonAudioFocusRequest(competingFocusRequest)
            setAudioMixing(false)
        }
    }

    @Test
    fun enablingAudioMixingResumesTheFocusInterruptedTrack() {
        setAudioMixing(false)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val competingFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        val competingPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(audioFile.absolutePath)
            isLooping = true
            prepare()
        }

        try {
            onMain { connection.playQueue(testSongs().take(1), startIndex = 0) }
            waitUntil { connection.state.value.isPlaying }
            assertEquals(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(competingFocusRequest),
            )
            competingPlayer.start()
            waitUntil { !connection.state.value.isPlaying }
            val interruptedPositionMs = connection.state.value.positionMs

            setAudioMixing(true)
            waitUntil {
                connection.state.value.audioMixingEnabled &&
                    connection.state.value.isPlaying &&
                    connection.state.value.mediaId == 1L
            }
            waitUntil { audioManager.activePlaybackConfigurations.size >= 2 }

            assertTrue(competingPlayer.isPlaying)
            assertTrue(connection.state.value.positionMs >= interruptedPositionMs)
        } finally {
            onMain { connection.stop() }
            runCatching { competingPlayer.stop() }
            runCatching { competingPlayer.release() }
            audioManager.abandonAudioFocusRequest(competingFocusRequest)
            setAudioMixing(false)
        }
    }

    @Test
    fun enablingAudioMixingAfterManualPauseKeepsTheTrackPaused() {
        setAudioMixing(false)
        try {
            onMain { connection.playQueue(testSongs().take(1), startIndex = 0) }
            waitUntil { connection.state.value.isPlaying }
            onMain { connection.togglePlayPause() }
            waitUntil { !connection.state.value.isPlaying }
            val pausedPosition = connection.state.value.positionMs

            setAudioMixing(true)
            SystemClock.sleep(750L)

            assertTrue(connection.state.value.audioMixingEnabled)
            assertTrue(!connection.state.value.isPlaying)
            assertEquals(1L, connection.state.value.mediaId)
            assertTrue(connection.state.value.positionMs in pausedPosition..(pausedPosition + 250L))
        } finally {
            onMain { connection.stop() }
            setAudioMixing(false)
        }
    }

    @Test
    fun hotAudioMixingTogglePreservesQueueAndRestoresStandardFocusBehavior() {
        setAudioMixing(false)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val competingFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        val competingPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(audioFile.absolutePath)
            isLooping = true
            prepare()
        }

        try {
            onMain {
                connection.setPlaybackMode(PlaybackMode.REVERSE)
                connection.playQueue(testSongs(), startIndex = 1)
            }
            waitUntil {
                connection.state.value.isPlaying &&
                    connection.state.value.mediaId == 2L &&
                    connection.state.value.playbackMode == PlaybackMode.REVERSE
            }
            val initialPosition = connection.state.value.positionMs
            val initialQueue = connection.state.value.queueItems.map { it.mediaId }

            setAudioMixing(true)
            waitUntil {
                connection.state.value.audioMixingEnabled && connection.state.value.isPlaying
            }
            setAudioMixing(false)
            waitUntil {
                !connection.state.value.audioMixingEnabled && connection.state.value.isPlaying
            }

            assertEquals(2L, connection.state.value.mediaId)
            assertEquals(initialQueue, connection.state.value.queueItems.map { it.mediaId })
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
            assertTrue(connection.state.value.positionMs >= initialPosition)

            assertEquals(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(competingFocusRequest),
            )
            competingPlayer.start()
            waitUntil { !connection.state.value.isPlaying }
            val interruptedPosition = connection.state.value.positionMs

            competingPlayer.stop()
            audioManager.abandonAudioFocusRequest(competingFocusRequest)
            waitUntil(timeoutMs = 5_000L) { connection.state.value.isPlaying }

            assertEquals(2L, connection.state.value.mediaId)
            assertEquals(initialQueue, connection.state.value.queueItems.map { it.mediaId })
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
            assertTrue(connection.state.value.positionMs >= interruptedPosition)
        } finally {
            runCatching { competingPlayer.release() }
            audioManager.abandonAudioFocusRequest(competingFocusRequest)
            onMain { connection.setPlaybackMode(PlaybackMode.SEQUENTIAL) }
            setAudioMixing(false)
        }
    }

    private fun testSongs(): List<Song> = listOf(
        song(1L, "First", "Artist 1", "Album 1", Uri.parse("content://art/1")),
        song(2L, "Second", "Artist 2", "Album 2", Uri.parse("content://art/2")),
    )

    private fun song(id: Long, title: String, artist: String, album: String, artwork: Uri?) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = id,
        durationMs = 20_000L,
        trackNumber = id.toInt(),
        dateAddedSeconds = id,
        contentUri = Uri.fromFile(audioFile),
        albumArtUri = artwork,
    )

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private fun <T> readController(controller: MediaController, block: MediaController.() -> T): T {
        val value = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            value.set(controller.block())
        }
        return value.get()
    }

    private fun waitForServiceMode(expected: PlaybackMode) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = future.get(10, TimeUnit.SECONDS)
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val observed = AtomicReference<PlaybackMode?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    observed.set(observer.sessionExtras.playbackModeOrNull())
                }
                if (observed.get() == expected) return
                SystemClock.sleep(50L)
            }
            throw AssertionError("service did not publish playback mode $expected")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MediaController.releaseFuture(future)
            }
        }
    }

    private fun setAudioMixing(enabled: Boolean) {
        onMain {
            connection.setAudioMixingEnabled(!enabled)
            connection.setAudioMixingEnabled(enabled)
        }
        waitForServiceAudioMixing(enabled)
    }

    private fun waitForServiceAudioMixing(expected: Boolean) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = future.get(10, TimeUnit.SECONDS)
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val observed = AtomicReference<Boolean?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    observed.set(observer.sessionExtras.audioMixingEnabledOrNull())
                }
                if (observed.get() == expected) return
                SystemClock.sleep(50L)
            }
            throw AssertionError("service did not publish audio mixing=$expected")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MediaController.releaseFuture(future)
            }
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("condition not met; latest playback state=${connection.state.value}")
    }

    private fun writeSilentWave(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val dataSize = sampleRate * durationSeconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        file.outputStream().buffered().use { output ->
            output.write(header)
            output.write(ByteArray(dataSize))
        }
    }
}
