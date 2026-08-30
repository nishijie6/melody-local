package com.melody.local.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melody.local.data.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private fun testSongs(): List<Song> = listOf(
        song(1L, "First", "Artist 1", "Album 1", Uri.parse("content://art/1")),
        song(2L, "Second", "Artist 2", "Album 2", Uri.parse("content://art/2")),
    )

    private fun song(id: Long, title: String, artist: String, album: String, artwork: Uri) = Song(
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

