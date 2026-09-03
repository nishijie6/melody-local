package com.melody.local.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.melody.local.data.Song
import com.melody.local.systemlyrics.AudioOutputRoute
import com.melody.local.systemlyrics.SystemLyricsSessionContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val mediaId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val lyricDelayMs: Long = 0L,
    val lyricsContentRevision: Long = 0L,
    val audioOutputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN,
)

interface PlaybackController {
    val state: StateFlow<PlaybackUiState>
    fun playQueue(songs: List<Song>, startIndex: Int)
    fun togglePlayPause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekToNext()
    fun seekToPrevious()
    fun setPlaybackMode(mode: PlaybackMode)
    fun release()
}

@OptIn(markerClass = [UnstableApi::class])
class PlayerConnection(context: Context) :
    PlaybackController,
    Player.Listener,
    MediaController.Listener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var playbackModeCommandId = 0L
    private var released = false
    private var currentPlaybackMode: PlaybackMode = PlaybackMode.SEQUENTIAL
    private var currentLyricDelayMs: Long = 0L
    private var currentLyricsContentRevision: Long = 0L
    private var currentAudioOutputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN

    private val _state = MutableStateFlow(PlaybackUiState(playbackMode = currentPlaybackMode))
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    init {
        connect()
    }

    private fun connect() {
        if (released || controllerFuture != null) return
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, MusicService::class.java),
        )
        val future = MediaController.Builder(appContext, sessionToken)
            .setListener(this)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connectedController ->
                        if (released || controllerFuture !== future) {
                            MediaController.releaseFuture(future)
                            return@onSuccess
                        }
                        controller = connectedController
                        reconnectAttempts = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                        connectedController.addListener(this)
                        updateSessionExtras(connectedController.sessionExtras)
                        while (pendingActions.isNotEmpty()) {
                            pendingActions.removeFirst()(connectedController)
                        }
                        updateState()
                    }
                    .onFailure {
                        if (controllerFuture === future) controllerFuture = null
                        scheduleReconnect()
                    }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    override fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        withController { player ->
            val safeStartIndex = startIndex.coerceIn(songs.indices)
            player.setMediaItems(songs.map { it.asMediaItem() }, safeStartIndex, 0L)
            // Reapply the service-owned policy after replacing the queue. This is especially
            // important when an asynchronous random-order request was created for the old size.
            sendPlaybackMode(player, currentPlaybackMode)
            player.prepare()
            player.play()
        }
    }

    override fun togglePlayPause() = withController { player ->
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    override fun stop() {
        withController { player ->
            player.stop()
            player.clearMediaItems()
            updateState()
        }
    }

    override fun seekTo(positionMs: Long) = withController { player ->
        val duration = player.safeDuration()
        player.seekTo(positionMs.coerceIn(0L, duration.coerceAtLeast(0L)))
        updateState()
    }

    override fun seekToNext() = withController { it.seekToNextMediaItem() }

    override fun seekToPrevious() = withController { player ->
        if (player.currentPosition > 5_000L) player.seekTo(0L) else player.seekToPreviousMediaItem()
    }

    override fun setPlaybackMode(mode: PlaybackMode) {
        if (mode == currentPlaybackMode) return
        currentPlaybackMode = mode
        _state.value = _state.value.copy(playbackMode = mode)
        withController { player -> sendPlaybackMode(player, mode) }
    }

    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
        if (released || this.controller !== controller) return
        updateSessionExtras(extras)
    }

    override fun onDisconnected(controller: MediaController) {
        if (released || this.controller !== controller) return
        playbackModeCommandId++
        val disconnectedFuture = controllerFuture
        controller.removeListener(this)
        progressJob?.cancel()
        progressJob = null
        _state.value = _state.value.copy(isPlaying = false)
        this.controller = null
        controllerFuture = null
        if (disconnectedFuture != null) {
            MediaController.releaseFuture(disconnectedFuture)
        } else {
            controller.release()
        }
        scheduleReconnect()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        updateState()
    }

    private fun updateState() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        _state.value = PlaybackUiState(
            mediaId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkUri = metadata.artworkUri,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.safeDuration(),
            playbackMode = currentPlaybackMode,
            lyricDelayMs = currentLyricDelayMs,
            lyricsContentRevision = currentLyricsContentRevision,
            audioOutputRoute = currentAudioOutputRoute,
        )
        updateProgressLoop(player.isPlaying)
    }

    private fun updateProgressLoop(isPlaying: Boolean) {
        if (!isPlaying) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                delay(400L)
                updateState()
            }
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val activeController = controller
        if (activeController != null) {
            action(activeController)
        } else {
            if (pendingActions.size >= MAX_PENDING_ACTIONS) pendingActions.removeFirst()
            pendingActions.addLast(action)
            connect()
        }
    }

    override fun release() {
        released = true
        playbackModeCommandId++
        progressJob?.cancel()
        reconnectJob?.cancel()
        pendingActions.clear()
        controller?.removeListener(this)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        controllerFuture = null
        scope.cancel()
    }

    private fun scheduleReconnect() {
        if (released || reconnectJob?.isActive == true) return
        val delayMillis = 500L * (1L shl reconnectAttempts.coerceAtMost(4))
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delayMillis)
            reconnectJob = null
            connect()
        }
    }

    private fun Player.safeDuration(): Long =
        duration.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L

    private fun sendPlaybackMode(player: MediaController, mode: PlaybackMode) {
        val commandId = ++playbackModeCommandId
        val requestedMode = mode
        val future = player.sendCustomCommand(
            SET_PLAYBACK_MODE_COMMAND,
            playbackModeBundle(requestedMode),
        )
        future.addListener(
            {
                val result = runCatching { future.get() }.getOrNull()
                if (
                    !released &&
                    controller === player &&
                    commandId == playbackModeCommandId &&
                    currentPlaybackMode == requestedMode &&
                    result?.resultCode != SessionResult.RESULT_SUCCESS
                ) {
                    updateSessionExtras(player.sessionExtras)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun updateSessionExtras(extras: Bundle) {
        extras.playbackModeOrNull()?.let { currentPlaybackMode = it }
        currentLyricDelayMs = extras.getLong(
            SystemLyricsSessionContract.EXTRA_APPLIED_DELAY_MS,
            currentLyricDelayMs,
        )
        currentLyricsContentRevision = extras.getLong(
            SystemLyricsSessionContract.EXTRA_CONTENT_REVISION,
            currentLyricsContentRevision,
        )
        currentAudioOutputRoute = extras.getString(
            SystemLyricsSessionContract.EXTRA_AUDIO_OUTPUT_ROUTE,
        )?.let { stored -> runCatching { AudioOutputRoute.valueOf(stored) }.getOrNull() }
            ?: currentAudioOutputRoute
        _state.value = _state.value.copy(
            playbackMode = currentPlaybackMode,
            lyricDelayMs = currentLyricDelayMs,
            lyricsContentRevision = currentLyricsContentRevision,
            audioOutputRoute = currentAudioOutputRoute,
        )
    }

    private fun Song.asMediaItem(): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
        albumArtUri?.let(metadataBuilder::setArtworkUri)
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private companion object {
        const val MAX_PENDING_ACTIONS = 32
    }
}
