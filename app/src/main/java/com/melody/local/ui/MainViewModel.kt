package com.melody.local.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melody.local.data.MusicRepository
import com.melody.local.data.MusicLibrary
import com.melody.local.data.PlaylistNameException
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.PlaylistRepository
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.RoomMoveJournalStore
import com.melody.local.data.RoomSongMetadataStore
import com.melody.local.data.Song
import com.melody.local.lyrics.LyricsRepository
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import com.melody.local.playback.PlayerConnection
import com.melody.local.playback.PlaybackController
import com.melody.local.media.MediaAuthorizationRequest
import com.melody.local.media.MediaOperationState
import com.melody.local.media.MediaStoreSongRelocationCoordinator
import com.melody.local.media.PlaylistMovePreview
import com.melody.local.media.RelocationStep
import com.melody.local.media.SongRelocationCoordinator
import com.melody.local.media.VideoAudioExtractor
import com.melody.local.media.VideoImportDraft
import com.melody.local.media.VideoImportRequest
import com.melody.local.media.WorkManagerVideoAudioExtractor
import com.melody.local.media.defaultVideoTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun filterAndSortSongs(
    songs: List<Song>,
    query: String,
    sort: SongSort,
): List<Song> {
    val filtered = if (query.isBlank()) {
        songs
    } else {
        songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true)
        }
    }
    return when (sort) {
        SongSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        SongSort.ARTIST -> filtered.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        SongSort.RECENT -> filtered.sortedByDescending { it.dateAddedSeconds }
    }
}

enum class SongSort {
    TITLE,
    ARTIST,
    RECENT,
}

sealed interface LyricsUiState {
    data object NoSong : LyricsUiState
    data object Loading : LyricsUiState
    data object Missing : LyricsUiState
    data class Ready(val lyrics: ParsedLyrics) : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel internal constructor(
    application: Application,
    private val musicRepository: MusicLibrary,
    private val playlistRepository: PlaylistStore,
    private val lyricsRepository: LyricsStore,
    val player: PlaybackController,
    private val videoAudioExtractor: VideoAudioExtractor? = null,
    private val songRelocationCoordinator: SongRelocationCoordinator? = null,
) : AndroidViewModel(application) {
    private constructor(application: Application, services: ProductionServices) : this(
        application = application,
        musicRepository = services.musicRepository,
        playlistRepository = services.playlists,
        lyricsRepository = services.lyrics,
        player = services.player,
        videoAudioExtractor = services.videoExtractor,
        songRelocationCoordinator = services.relocation,
    )

    constructor(application: Application) : this(application, ProductionServices(application))

    val playback = player.state

    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(SongSort.TITLE)
    val sort: StateFlow<SongSort> = _sort.asStateFlow()

    val visibleSongs: StateFlow<List<Song>> = combine(_allSongs, _query, _sort) { songs, query, sort ->
        filterAndSortSongs(songs, query, sort)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistSongs: StateFlow<List<Song>> = selectedPlaylistId
        .flatMapLatest { playlistId ->
            if (playlistId == null) flowOf(emptyList())
            else playlistRepository.observeSongIds(playlistId)
        }
        .combine(_allSongs) { ids, songs ->
            val songsById = songs.associateBy { it.id }
            ids.mapNotNull(songsById::get)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val lyricRevision = MutableStateFlow(0)
    val lyrics: StateFlow<LyricsUiState> = combine(
        playback.map { it.mediaId }.distinctUntilChanged(),
        lyricRevision,
    ) { songId, _ -> songId }
        .flatMapLatest { songId ->
            if (songId == null) flowOf(LyricsUiState.NoSong)
            else lyricFlow(songId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState.NoSong)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val _videoImportDraft = MutableStateFlow<VideoImportDraft?>(null)
    val videoImportDraft: StateFlow<VideoImportDraft?> = _videoImportDraft.asStateFlow()

    private val _videoImportState = MutableStateFlow<MediaOperationState>(MediaOperationState.Idle)
    val videoImportState: StateFlow<MediaOperationState> = _videoImportState.asStateFlow()

    private val _playlistMovePreview = MutableStateFlow<PlaylistMovePreview?>(null)
    val playlistMovePreview: StateFlow<PlaylistMovePreview?> = _playlistMovePreview.asStateFlow()

    private val _playlistMoveState = MutableStateFlow<MediaOperationState>(MediaOperationState.Idle)
    val playlistMoveState: StateFlow<MediaOperationState> = _playlistMoveState.asStateFlow()

    private val _authorizationRequest = MutableStateFlow<MediaAuthorizationRequest?>(null)
    val authorizationRequest: StateFlow<MediaAuthorizationRequest?> =
        _authorizationRequest.asStateFlow()

    private var videoMonitorJob: Job? = null
    private var relocationJob: Job? = null

    init {
        if (videoAudioExtractor != null) monitorVideoImport(showCompletionMessage = false)
        if (songRelocationCoordinator != null) {
            relocationJob = viewModelScope.launch {
                val recovered = runCatching {
                    withContext(Dispatchers.IO) {
                        songRelocationCoordinator.recover(::updateMoveState)
                    }
                }.getOrElse { error ->
                    updateMoveState(
                        MediaOperationState.Failed(error.message ?: "无法恢复上次的文件移动")
                    )
                    null
                }
                if (recovered != null) handleRelocationStep(recovered)
            }
        }
    }

    fun refreshSongs() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _libraryError.value = null
            runCatching { musicRepository.loadSongs() }
                .onSuccess { _allSongs.value = it }
                .onFailure {
                    _libraryError.value = "读取本地音乐失败，请检查音乐权限"
                }
            _isLoading.value = false
        }
    }

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun updateSort(value: SongSort) {
        _sort.value = value
    }

    fun playSongs(songs: List<Song>, startIndex: Int) = player.playQueue(songs, startIndex)

    fun selectPlaylist(playlistId: Long?) {
        selectedPlaylistId.value = playlistId
    }

    fun createPlaylist(name: String, songIdToAdd: Long? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val id = playlistRepository.create(name)
                if (songIdToAdd != null) playlistRepository.addSong(id, songIdToAdd)
                _messages.emit(if (songIdToAdd == null) "歌单已创建" else "歌单已创建，歌曲已加入")
            } catch (error: PlaylistNameException) {
                _messages.emit(error.message ?: "歌单名称无效")
            }
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                playlistRepository.rename(playlistId, name)
                _messages.emit("歌单已重命名")
            } catch (error: PlaylistNameException) {
                _messages.emit(error.message ?: "歌单名称无效")
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.delete(playlistId)
            if (selectedPlaylistId.value == playlistId) selectedPlaylistId.value = null
            _messages.emit("歌单已删除")
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            val added = playlistRepository.addSong(playlistId, songId)
            _messages.emit(if (added) "已加入歌单" else "歌曲已在歌单中")
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            playlistRepository.removeSong(playlistId, songId)
            _messages.emit("已从歌单移除")
        }
    }

    fun prepareVideoImport(uri: Uri) {
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
            _videoImportDraft.value = VideoImportDraft(
                uri = uri,
                suggestedTitle = defaultVideoTitle(displayName),
            )
        }
    }

    fun dismissVideoImportDraft() {
        _videoImportDraft.value = null
    }

    fun importVideoAudio(
        title: String,
        artist: String,
        album: String,
        extractArtwork: Boolean,
    ) {
        val draft = _videoImportDraft.value ?: return
        if (title.isBlank()) {
            _messages.tryEmit("歌曲标题不能为空")
            return
        }
        val extractor = videoAudioExtractor ?: return
        viewModelScope.launch {
            val enqueued = extractor.enqueue(
                VideoImportRequest(
                    uri = draft.uri,
                    title = title.trim(),
                    artist = artist.trim().ifBlank { "未知歌手" },
                    album = album.trim().ifBlank { "视频提取" },
                    extractArtwork = extractArtwork,
                )
            )
            if (!enqueued) {
                _messages.emit("已有视频音轨正在导入")
                return@launch
            }
            _videoImportDraft.value = null
            _videoImportState.value = MediaOperationState.Preparing("正在准备视频音轨…")
            monitorVideoImport(showCompletionMessage = true)
        }
    }

    fun cancelVideoImport() {
        val extractor = videoAudioExtractor ?: return
        viewModelScope.launch {
            extractor.cancel()
            _videoImportState.value = MediaOperationState.Cancelled()
            _messages.emit("视频音轨导入已取消")
        }
    }

    fun dismissVideoImportResult() {
        if (_videoImportState.value.isTerminal()) _videoImportState.value = MediaOperationState.Idle
    }

    fun loadPlaylistMovePreview() {
        val coordinator = songRelocationCoordinator ?: return
        viewModelScope.launch {
            _playlistMovePreview.value = null
            _playlistMoveState.value = MediaOperationState.Preparing("正在统计歌单歌曲…")
            runCatching { coordinator.preview() }
                .onSuccess {
                    _playlistMovePreview.value = it
                    _playlistMoveState.value = MediaOperationState.Idle
                }
                .onFailure {
                    _playlistMoveState.value = MediaOperationState.Failed(
                        it.message ?: "无法统计歌单歌曲"
                    )
                }
        }
    }

    fun startPlaylistMove(folderName: String) {
        val coordinator = songRelocationCoordinator ?: return
        if (_playlistMoveState.value.isBusy()) {
            _messages.tryEmit("已有歌单歌曲汇总任务正在进行")
            return
        }
        player.stop()
        relocationJob = viewModelScope.launch {
            val step = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.start(folderName, ::updateMoveState)
                }
            }.getOrElse { error ->
                val failed = MediaOperationState.Failed(error.message ?: "汇总歌单歌曲失败")
                updateMoveState(failed)
                RelocationStep.Finished(failed)
            }
            handleRelocationStep(step)
        }
    }

    fun resumePlaylistMove(authorizationGranted: Boolean) {
        val coordinator = songRelocationCoordinator ?: return
        _authorizationRequest.value = null
        relocationJob = viewModelScope.launch {
            val step = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.resume(authorizationGranted, ::updateMoveState)
                }
            }.getOrElse { error ->
                val failed = MediaOperationState.Failed(error.message ?: "系统授权处理失败")
                updateMoveState(failed)
                RelocationStep.Finished(failed)
            }
            handleRelocationStep(step)
        }
    }

    fun cancelPlaylistMove() {
        val coordinator = songRelocationCoordinator ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { coordinator.cancel(::updateMoveState) }
            refreshSongs()
        }
    }

    fun authorizationRequestLaunched() {
        _authorizationRequest.value = null
    }

    fun dismissPlaylistMoveResult() {
        if (_playlistMoveState.value.isTerminal()) {
            _playlistMoveState.value = MediaOperationState.Idle
            _playlistMovePreview.value = null
        }
    }

    fun importLyrics(songId: Long, uri: Uri) {
        viewModelScope.launch {
            runCatching { lyricsRepository.import(songId, uri) }
                .onSuccess {
                    lyricRevision.value++
                    _messages.emit("歌词已导入")
                }
                .onFailure { _messages.emit(it.message ?: "歌词导入失败") }
        }
    }

    fun deleteCurrentLyrics() {
        val songId = playback.value.mediaId ?: return
        viewModelScope.launch {
            lyricsRepository.delete(songId)
            lyricRevision.value++
            _messages.emit("歌词已移除")
        }
    }

    private fun lyricFlow(songId: Long): Flow<LyricsUiState> = flow {
        emit(LyricsUiState.Loading)
        val result = runCatching { lyricsRepository.load(songId) }
        emit(
            result.fold(
                onSuccess = { parsed ->
                    if (parsed == null) LyricsUiState.Missing else LyricsUiState.Ready(parsed)
                },
                onFailure = { LyricsUiState.Error(it.message ?: "歌词加载失败") },
            )
        )
    }

    private fun monitorVideoImport(showCompletionMessage: Boolean) {
        val extractor = videoAudioExtractor ?: return
        videoMonitorJob?.cancel()
        videoMonitorJob = viewModelScope.launch {
            var lastState: MediaOperationState? = null
            while (true) {
                val state = runCatching { extractor.currentState() }
                    .getOrElse { MediaOperationState.Failed(it.message ?: "无法读取导入进度") }
                _videoImportState.value = state
                if (state.isTerminal()) {
                    if (state is MediaOperationState.Completed) refreshSongs()
                    if (showCompletionMessage && state != lastState) {
                        _messages.emit(
                            when (state) {
                                is MediaOperationState.Completed -> "视频音轨已导入到 Music/音澜/视频提取"
                                is MediaOperationState.Failed -> state.message
                                is MediaOperationState.Cancelled -> "视频音轨导入已取消"
                                else -> ""
                            }
                        )
                    }
                    break
                }
                if (state is MediaOperationState.Idle) break
                lastState = state
                delay(400L)
            }
        }
    }

    private fun updateMoveState(state: MediaOperationState) {
        _playlistMoveState.value = state
    }

    private suspend fun handleRelocationStep(step: RelocationStep) {
        when (step) {
            is RelocationStep.AwaitingAuthorization -> _authorizationRequest.value = step.request
            is RelocationStep.Finished -> {
                when (val state = step.state) {
                    is MediaOperationState.Completed -> {
                        refreshSongs()
                        val summary = state.summary
                        _messages.emit(
                            "汇总完成：移动 ${summary.moved} 首，跳过 ${summary.skipped} 首，" +
                                "失败 ${summary.failed} 首，取消 ${summary.cancelled} 首"
                        )
                    }
                    is MediaOperationState.Cancelled -> {
                        refreshSongs()
                        _messages.emit("歌单歌曲汇总已取消，已完成部分会保留")
                    }
                    is MediaOperationState.Failed -> _messages.emit(state.message)
                    else -> Unit
                }
            }
        }
    }

    private fun MediaOperationState.isBusy(): Boolean =
        this is MediaOperationState.Preparing ||
            this is MediaOperationState.Processing ||
            this is MediaOperationState.AwaitingSystemAuthorization

    private fun MediaOperationState.isTerminal(): Boolean =
        this is MediaOperationState.Completed ||
            this is MediaOperationState.Failed ||
            this is MediaOperationState.Cancelled

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

private class ProductionServices(application: Application) {
    private val database = PlaylistDatabase.getInstance(application)
    private val metadata = RoomSongMetadataStore(database.songStateDao())
    val playlists = PlaylistRepository(database.playlistDao())
    val lyrics = LyricsRepository(application)
    val player = PlayerConnection(application)
    val musicRepository = MusicRepository(application, metadata)
    val videoExtractor = WorkManagerVideoAudioExtractor(application)
    val relocation = MediaStoreSongRelocationCoordinator(
        context = application,
        playlists = playlists,
        metadata = metadata,
        lyrics = lyrics,
        journal = RoomMoveJournalStore(database.songStateDao()),
    )
}
