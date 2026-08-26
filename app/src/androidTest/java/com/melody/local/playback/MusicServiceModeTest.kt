package com.melody.local.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MusicServiceModeTest {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private lateinit var controller: MediaController

    @Before
    fun connectController() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        controllerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        controller = controllerFuture.get(10, TimeUnit.SECONDS)
        onControllerThread { clearMediaItems() }
    }

    @After
    fun releaseController() {
        onControllerThread {
            stop()
            clearMediaItems()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MediaController.releaseFuture(controllerFuture)
        }
    }

    @Test
    fun serviceOwnsCanonicalQueueAcrossControllerReconnection() {
        onControllerThread {
            setMediaItems(
                listOf(mediaItem("1"), mediaItem("2"), mediaItem("3")),
                1,
                0L,
            )
        }

        assertMode(PlaybackMode.REVERSE)
        assertEquals(listOf("1", "2", "3"), onControllerThread { mediaIds() })
        assertEquals("2", onControllerThread { currentMediaItem?.mediaId })
        assertEquals(Player.REPEAT_MODE_ALL, onControllerThread { repeatMode })
        onControllerThread { seekToNextMediaItem() }
        assertEquals("1", onControllerThread { currentMediaItem?.mediaId })

        reconnectControllerKeepingSession()

        assertMode(PlaybackMode.SEQUENTIAL)
        assertEquals(listOf("1", "2", "3"), onControllerThread { mediaIds() })
        assertEquals("1", onControllerThread { currentMediaItem?.mediaId })
        assertEquals(Player.REPEAT_MODE_OFF, onControllerThread { repeatMode })

        assertMode(PlaybackMode.RANDOM)
        val visited = mutableSetOf(onControllerThread { currentMediaItem!!.mediaId })
        repeat(2) {
            onControllerThread { seekToNextMediaItem() }
            visited += onControllerThread { currentMediaItem!!.mediaId }
        }
        assertEquals(setOf("1", "2", "3"), visited)
    }

    @Test
    fun rejectsMalformedPlaybackModeCommands() {
        val future = onControllerThread {
            sendCustomCommand(SET_PLAYBACK_MODE_COMMAND, Bundle.EMPTY)
        }

        assertEquals(
            SessionError.ERROR_BAD_VALUE,
            future.get(10, TimeUnit.SECONDS).resultCode,
        )
    }

    @Test
    fun appliesLoopAndSingleRepeatModesAndRejectsUnknownCommands() {
        onControllerThread {
            setMediaItems(listOf(mediaItem("1"), mediaItem("2")), 0, 0L)
        }

        assertMode(PlaybackMode.LOOP)
        assertEquals(Player.REPEAT_MODE_ALL, onControllerThread { repeatMode })
        assertEquals(false, onControllerThread { shuffleModeEnabled })

        assertMode(PlaybackMode.SINGLE)
        assertEquals(Player.REPEAT_MODE_ONE, onControllerThread { repeatMode })
        assertEquals(false, onControllerThread { shuffleModeEnabled })

        val unknownResult = onControllerThread {
            sendCustomCommand(SessionCommand("com.melody.local.UNKNOWN", Bundle.EMPTY), Bundle.EMPTY)
        }.get(10, TimeUnit.SECONDS)
        assertEquals(false, unknownResult.resultCode == SessionResult.RESULT_SUCCESS)
    }

    private fun assertMode(mode: PlaybackMode) {
        val future = onControllerThread {
            sendCustomCommand(SET_PLAYBACK_MODE_COMMAND, playbackModeBundle(mode))
        }
        val result = future.get(10, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun reconnectControllerKeepingSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nextFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java)),
        ).buildAsync()
        val nextController = nextFuture.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MediaController.releaseFuture(controllerFuture)
        }
        controllerFuture = nextFuture
        controller = nextController
    }

    private fun mediaItem(id: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse("file:///nonexistent/$id.mp3"))
            .setMediaMetadata(MediaMetadata.Builder().build())
            .build()
    }

    private fun MediaController.mediaIds(): List<String> =
        List(mediaItemCount) { index -> getMediaItemAt(index).mediaId }

    private fun <T> onControllerThread(block: MediaController.() -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(runCatching { controller.block() })
        }
        return result.get().getOrThrow()
    }
}
