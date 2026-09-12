package com.melody.local.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import org.junit.Assert.assertSame
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
class PlaybackQueueContextInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var audioFile: File
    private lateinit var connection: PlayerConnection

    @Before
    fun setUp() {
        audioFile = File(context.cacheDir, "playback-queue-context-test.wav")
        writeSilentWave(audioFile, durationSeconds = 20)
        onMain {
            connection = PlayerConnection(context)
            connection.stop()
        }
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
    fun playlistPlaybackPublishesSourceAndSelectsWithoutLosingQueueContext() {
        val songs = testSongs()
        val source = PlaybackQueueSource.playlist(id = 701L, name = "通勤")
        val observerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = observerFuture.get(10, TimeUnit.SECONDS)

        try {
            onMain {
                connection.setPlaybackMode(PlaybackMode.REVERSE)
                connection.playQueue(songs, startIndex = 1, source = source)
            }
            waitUntil {
                val state = connection.state.value
                state.queueSource == source &&
                    state.queueIndex == 1 &&
                    state.isPlaying &&
                    state.playbackMode == PlaybackMode.REVERSE &&
                    state.queueItems.map(PlaybackQueueItem::title) == listOf("Queue first", "Queue second")
            }

            assertEquals(PlaybackQueueSourceType.PLAYLIST, connection.state.value.queueSource?.type)
            assertEquals(701L, connection.state.value.queueSource?.playlistId)
            assertEquals("通勤", connection.state.value.queueSource?.name)
            assertEquals(
                listOf(
                    PlaybackQueueItem(91_001L, "Queue first", "Queue artist 1", Uri.parse("content://queue-art/1")),
                    PlaybackQueueItem(91_002L, "Queue second", "Queue artist 2", Uri.parse("content://queue-art/2")),
                ),
                connection.state.value.queueItems,
            )
            waitUntil {
                readController(observer) {
                    mediaItemCount == 2 &&
                        currentMediaItemIndex == 1 &&
                        getMediaItemAt(0).mediaId == "91001" &&
                        getMediaItemAt(1).mediaId == "91002"
                }
            }
            val queueIdsBeforeSelection = readQueueMediaIds(observer)
            val queueSizeBeforeSelection = readController(observer) { mediaItemCount }
            val sourceBeforeSelection = connection.state.value.queueSource

            onMain { connection.seekToQueueItem(0) }
            waitUntil {
                connection.state.value.queueIndex == 0 &&
                    connection.state.value.mediaId == 91_001L &&
                    connection.state.value.isPlaying &&
                    readController(observer) { currentMediaItemIndex == 0 }
            }

            assertEquals(sourceBeforeSelection, connection.state.value.queueSource)
            assertEquals(queueSizeBeforeSelection, readController(observer) { mediaItemCount })
            assertEquals(queueIdsBeforeSelection, readQueueMediaIds(observer))
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
            assertTrue(connection.state.value.isPlaying)

            onMain {
                connection.seekToQueueItem(0)
                connection.seekToQueueItem(-1)
                connection.seekToQueueItem(songs.size)
            }
            assertEquals(0, connection.state.value.queueIndex)
            assertEquals(91_001L, connection.state.value.mediaId)
            assertEquals(queueIdsBeforeSelection, readQueueMediaIds(observer))
            assertEquals(PlaybackMode.REVERSE, connection.state.value.playbackMode)
        } finally {
            onMain {
                connection.setPlaybackMode(PlaybackMode.SEQUENTIAL)
                MediaController.releaseFuture(observerFuture)
            }
        }
    }

    @Test
    fun reconnectedConnectionReadsQueueAndSourceOwnedByService() {
        val source = PlaybackQueueSource.playlist(id = 702L, name = "晚间散步")
        onMain { connection.playQueue(testSongs(), startIndex = 0, source = source) }
        waitUntil {
            connection.state.value.queueSource == source &&
                connection.state.value.queueItems.size == 2 &&
                connection.state.value.isPlaying
        }

        onMain {
            connection.release()
            connection = PlayerConnection(context)
        }
        waitUntil {
            val state = connection.state.value
            state.queueSource == source &&
                state.queueIndex == 0 &&
                state.queueItems.map(PlaybackQueueItem::title) == listOf("Queue first", "Queue second") &&
                state.mediaId == 91_001L
        }

        assertEquals(listOf(91_001L, 91_002L), connection.state.value.queueItems.map { it.mediaId })
        assertTrue(connection.state.value.isPlaying)
    }

    @Test
    fun replayingIdenticalSongsFromAnotherPlaylistUpdatesSourceContext() {
        val songs = testSongs()
        val sourceA = PlaybackQueueSource.playlist(id = 703L, name = "歌单 A")
        val sourceB = PlaybackQueueSource.playlist(id = 704L, name = "歌单 B")

        onMain { connection.playQueue(songs, startIndex = 0, source = sourceA) }
        waitUntil { connection.state.value.queueSource == sourceA }

        onMain { connection.playQueue(songs, startIndex = 0, source = sourceB) }
        waitUntil {
            val state = connection.state.value
            state.queueSource == sourceB &&
                state.queueItems.map { it.mediaId } == listOf(91_001L, 91_002L)
        }

        // Read the result through a fresh controller connection so this assertion covers the
        // service-owned timeline, rather than only MediaController's optimistic local masking state.
        onMain {
            connection.release()
            connection = PlayerConnection(context)
        }
        waitUntil {
            connection.state.value.queueSource == sourceB &&
                connection.state.value.queueItems.map { it.mediaId } == listOf(91_001L, 91_002L)
        }

        assertEquals(704L, connection.state.value.queueSource?.playlistId)
        assertEquals("歌单 B", connection.state.value.queueSource?.name)
    }

    @Test
    fun displayOnlyMetadataChangeReusesTheProjectedQueueSnapshot() {
        val source = PlaybackQueueSource.playlist(id = 706L, name = "歌词性能")
        val observerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val observer = observerFuture.get(10, TimeUnit.SECONDS)
        try {
            onMain { connection.playQueue(testSongs(), startIndex = 0, source = source) }
            waitUntil {
                connection.state.value.queueSource == source &&
                    connection.state.value.queueItems.size == 2 &&
                    readController(observer) { currentMediaItem != null }
            }
            val queueBeforeDisplayUpdate = connection.state.value.queueItems
            val currentItem = readController(observer) { currentMediaItem!! }
            val displayOnlyMetadata = currentItem.mediaMetadata.buildUpon()
                .setDisplayTitle("仅改变通知歌词")
                .setSubtitle("不改变队列标题")
                .build()

            onMain {
                observer.replaceMediaItem(
                    0,
                    currentItem.buildUpon().setMediaMetadata(displayOnlyMetadata).build(),
                )
            }
            waitUntil {
                readController(observer) {
                    currentMediaItem?.mediaMetadata?.displayTitle?.toString() == "仅改变通知歌词"
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertSame(queueBeforeDisplayUpdate, connection.state.value.queueItems)
            assertEquals(source, connection.state.value.queueSource)
            assertEquals(listOf("Queue first", "Queue second"), connection.state.value.queueItems.map { it.title })
        } finally {
            onMain { MediaController.releaseFuture(observerFuture) }
        }
    }

    @Test
    fun stopClearsQueueSourceItemsAndCurrentIndex() {
        val source = PlaybackQueueSource.playlist(id = 705L, name = "待清空")
        onMain { connection.playQueue(testSongs(), startIndex = 0, source = source) }
        waitUntil {
            connection.state.value.queueSource == source && connection.state.value.queueItems.size == 2
        }

        onMain { connection.stop() }
        waitUntil {
            val state = connection.state.value
            state.mediaId == null &&
                state.queueSource == null &&
                state.queueItems.isEmpty() &&
                state.queueIndex == -1
        }

        assertEquals(0L, connection.state.value.durationMs)
    }

    private fun testSongs(): List<Song> = listOf(
        song(91_001L, "Queue first", "Queue artist 1", Uri.parse("content://queue-art/1")),
        song(91_002L, "Queue second", "Queue artist 2", Uri.parse("content://queue-art/2")),
    )

    private fun song(id: Long, title: String, artist: String, artwork: Uri?) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "Queue album",
        albumId = id,
        durationMs = 20_000L,
        trackNumber = id.toInt(),
        dateAddedSeconds = id,
        contentUri = Uri.fromFile(audioFile),
        albumArtUri = artwork,
    )

    private fun readQueueMediaIds(controller: MediaController): List<String> =
        readController(controller) {
            List(mediaItemCount) { index -> getMediaItemAt(index).mediaId }
        }

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
