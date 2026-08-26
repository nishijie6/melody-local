package com.melody.local.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melody.local.data.MusicRepository
import com.melody.local.data.MusicLibrary
import com.melody.local.data.PlaylistNameException
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.PlaylistRepository
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.Song
import com.melody.local.lyrics.LyricsRepository
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import com.melody.local.playback.PlayerConnection
import com.melody.local.playback.PlaybackController
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
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        musicRepository = MusicRepository(application),
        playlistRepository = PlaylistRepository(
            PlaylistDatabase.getInstance(application).playlistDao()
        ),
        lyricsRepository = LyricsRepository(application),
        player = PlayerConnection(application),
    )
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

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
