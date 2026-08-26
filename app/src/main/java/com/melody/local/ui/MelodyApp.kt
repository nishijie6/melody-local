@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.melody.local.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.Song
import com.melody.local.data.asDuration
import com.melody.local.lyrics.ParsedLyrics
import com.melody.local.playback.PlaybackUiState
import com.melody.local.playback.PlaybackMode
import com.melody.local.ui.theme.Coral
import com.melody.local.ui.theme.CoralSoft
import com.melody.local.ui.theme.Cream
import com.melody.local.ui.theme.Ink
import com.melody.local.ui.theme.InkSoft
import com.melody.local.ui.theme.Muted
import com.melody.local.ui.theme.SurfaceRaised
import com.melody.local.ui.theme.Violet
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private enum class HomeTab { SONGS, PLAYLISTS }

private const val RECORD_ROTATION_DURATION_MS = 18_000
private val NowPlayingGradientTop = Color(0xFF5A363E)
private val NowPlayingGradientMiddle = Color(0xFF2B2631)
private val NowPlayingGradientBottom = Color(0xFF19171F)
private val PlayerControlSurface = Color(0xFF6C607A)
private val ArtworkPalettes = listOf(
    listOf(Color(0xFFFF9B7E), Color(0xFF753B58)),
    listOf(Color(0xFFA793FF), Color(0xFF365477)),
    listOf(Color(0xFF7DD4C1), Color(0xFF31535B)),
    listOf(Color(0xFFFFC66D), Color(0xFF7C4E3A)),
)

@Composable
fun MelodyApp(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val visibleSongs by viewModel.visibleSongs.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistSongs by viewModel.selectedPlaylistSongs.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.SONGS) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var playlistPickerSong by remember { mutableStateOf<Song?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createWithSongId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var showSongPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(openPlaylistId) {
        viewModel.selectPlaylist(openPlaylistId)
    }

    if (!hasAudioPermission) {
        PermissionScreen(onRequestPermission = onRequestPermission)
        return
    }

    if (showPlayer && playback.mediaId != null) {
        BackHandler { showPlayer = false }
        NowPlayingScreen(
            playback = playback,
            lyricsState = lyrics,
            onBack = { showPlayer = false },
            onTogglePlayback = viewModel.player::togglePlayPause,
            onPrevious = viewModel.player::seekToPrevious,
            onNext = viewModel.player::seekToNext,
            onStopPlayback = viewModel.player::stop,
            onSeek = viewModel.player::seekTo,
            onPlaybackModeChange = viewModel.player::setPlaybackMode,
            onImportLyrics = viewModel::importLyrics,
            onDeleteLyrics = viewModel::deleteCurrentLyrics,
        )
        return
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (playback.mediaId != null) {
                    MiniPlayer(
                        playback = playback,
                        onClick = { showPlayer = true },
                        onTogglePlayback = viewModel.player::togglePlayPause,
                        onNext = viewModel.player::seekToNext,
                    )
                }
                NavigationBar(
                    containerColor = InkSoft,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.SONGS,
                        onClick = {
                            selectedTab = HomeTab.SONGS
                            openPlaylistId = null
                        },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text("歌曲") },
                        colors = navigationColors(),
                    )
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.PLAYLISTS,
                        onClick = {
                            selectedTab = HomeTab.PLAYLISTS
                            openPlaylistId = null
                        },
                        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                        label = { Text("歌单") },
                        colors = navigationColors(),
                    )
                }
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = openPlaylistId,
            label = "playlist_navigation",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) { playlistId ->
            if (playlistId != null) {
                val summary = playlists.firstOrNull { it.id == playlistId }
                PlaylistDetailScreen(
                    title = summary?.name ?: "歌单",
                    songs = playlistSongs,
                    currentSongId = playback.mediaId,
                    onBack = { openPlaylistId = null },
                    onPlay = { index -> viewModel.playSongs(playlistSongs, index) },
                    playbackMode = playback.playbackMode,
                    onRandomPlay = {
                        if (playlistSongs.isNotEmpty()) {
                            viewModel.player.setPlaybackMode(PlaybackMode.RANDOM)
                            viewModel.playSongs(playlistSongs, playlistSongs.indices.random())
                        }
                    },
                    onAddSongs = { showSongPicker = true },
                    onRemove = { song -> viewModel.removeSongFromPlaylist(playlistId, song.id) },
                    onOpenPlayer = { showPlayer = true },
                )
            } else {
                when (selectedTab) {
                    HomeTab.SONGS -> SongsScreen(
                        songs = visibleSongs,
                        totalSongCount = allSongs.size,
                        query = query,
                        sort = sort,
                        isLoading = isLoading,
                        error = libraryError,
                        currentSongId = playback.mediaId,
                        onQueryChange = viewModel::updateQuery,
                        onSortChange = viewModel::updateSort,
                        onRefresh = viewModel::refreshSongs,
                        onPlay = { index -> viewModel.playSongs(visibleSongs, index) },
                        onAddToPlaylist = { playlistPickerSong = it },
                        onOpenPlayer = { showPlayer = true },
                    )
                    HomeTab.PLAYLISTS -> PlaylistsScreen(
                        playlists = playlists,
                        onCreate = {
                            createWithSongId = null
                            showCreateDialog = true
                        },
                        onOpen = { openPlaylistId = it.id },
                        onRename = { renameTarget = it },
                        onDelete = { deleteTarget = it },
                    )
                }
            }
        }
    }

    playlistPickerSong?.let { song ->
        PlaylistPickerDialog(
            song = song,
            playlists = playlists,
            onDismiss = { playlistPickerSong = null },
            onSelect = { playlist ->
                viewModel.addSongToPlaylist(playlist.id, song.id)
                playlistPickerSong = null
            },
            onCreate = {
                createWithSongId = song.id
                playlistPickerSong = null
                showCreateDialog = true
            },
        )
    }

    if (showCreateDialog) {
        NameDialog(
            title = "新建歌单",
            confirmLabel = "创建",
            initialValue = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createPlaylist(name, createWithSongId)
                showCreateDialog = false
                createWithSongId = null
            },
        )
    }

    renameTarget?.let { playlist ->
        NameDialog(
            title = "重命名歌单",
            confirmLabel = "保存",
            initialValue = playlist.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renamePlaylist(playlist.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除“${playlist.name}”？") },
            text = { Text("歌单会被删除，本地歌曲文件不会受到影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (openPlaylistId == playlist.id) openPlaylistId = null
                        viewModel.deletePlaylist(playlist.id)
                        deleteTarget = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (showSongPicker && openPlaylistId != null) {
        val existingIds = playlistSongs.mapTo(hashSetOf()) { it.id }
        SongPickerDialog(
            songs = allSongs.filterNot { it.id in existingIds },
            onDismiss = { showSongPicker = false },
            onSelect = { song -> viewModel.addSongToPlaylist(openPlaylistId!!, song.id) },
        )
    }
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Ink,
    selectedTextColor = CoralSoft,
    indicatorColor = Coral,
    unselectedIconColor = Muted,
    unselectedTextColor = Muted,
)

@Composable
internal fun PermissionScreen(onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2A1E25), Ink, Ink)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .shadow(30.dp, CircleShape, ambientColor = Coral, spotColor = Coral)
                    .background(Coral, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(54.dp),
                )
            }
            Spacer(Modifier.height(36.dp))
            Text("让音乐住进这里", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "音澜需要读取设备上的音频，才能建立你的离线曲库。歌曲只在本机处理，不会上传。",
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("允许访问音乐")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }
            ) { Text("已拒绝权限？前往系统设置", color = Muted) }
        }
    }
}

@Composable
private fun SongsScreen(
    songs: List<Song>,
    totalSongCount: Int,
    query: String,
    sort: SongSort,
    isLoading: Boolean,
    error: String?,
    currentSongId: Long?,
    onQueryChange: (String) -> Unit,
    onSortChange: (SongSort) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Int) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            LibraryHeader(totalSongCount)
            SearchField(query = query, onQueryChange = onQueryChange)
            SortRow(sort = sort, onSortChange = onSortChange)
        }

        when {
            isLoading && songs.isEmpty() -> item { LoadingState("正在整理本地曲库…") }
            error != null && songs.isEmpty() -> item {
                MessageState(
                    icon = Icons.Rounded.Refresh,
                    title = "曲库加载失败",
                    message = error,
                    actionLabel = "重试",
                    onAction = onRefresh,
                )
            }
            songs.isEmpty() && query.isNotBlank() -> item {
                MessageState(
                    icon = Icons.Rounded.Search,
                    title = "没有找到相关歌曲",
                    message = "换个歌名、歌手或专辑试试",
                )
            }
            songs.isEmpty() -> item {
                MessageState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "还没有发现音乐",
                    message = "把音频文件保存到设备的 Music 或 Download 目录后刷新曲库",
                    actionLabel = "刷新曲库",
                    onAction = onRefresh,
                )
            }
            else -> {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (query.isBlank()) "全部歌曲" else "搜索结果",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${songs.size} 首", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        isPlaying = song.id == currentSongId,
                        onClick = {
                            if (song.id == currentSongId) onOpenPlayer() else onPlay(index)
                        },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(totalSongCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("我的音乐", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (totalSongCount == 0) "等待第一段旋律" else "$totalSongCount 首歌 · 全部离线可听",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    Brush.linearGradient(listOf(Coral, Violet)),
                    RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Ink)
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp)),
        placeholder = { Text("搜索歌曲、歌手或专辑") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = InkSoft,
            unfocusedContainerColor = InkSoft,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SortRow(sort: SongSort, onSortChange: (SongSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        SortChip("标题", sort == SongSort.TITLE) { onSortChange(SongSort.TITLE) }
        SortChip("歌手", sort == SongSort.ARTIST) { onSortChange(SongSort.ARTIST) }
        SortChip("最近添加", sort == SongSort.RECENT) { onSortChange(SongSort.RECENT) }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Coral,
            selectedLabelColor = Ink,
            selectedLeadingIconColor = Ink,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = SurfaceRaised,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SongRow(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        if (isPlaying) Coral.copy(alpha = 0.09f) else Color.Transparent,
        label = "song_background",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtwork(song.albumArtUri, song.title, size = 54.dp, corner = 15.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Coral,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    song.title,
                    color = if (isPlaying) CoralSoft else Cream,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${song.artist}  ·  ${song.durationMs.asDuration()}",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Muted)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (onAddToPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("加入歌单") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist()
                        },
                    )
                }
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text("从歌单移除") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(
    playlists: List<PlaylistSummary>,
    onCreate: () -> Unit,
    onOpen: (PlaylistSummary) -> Unit,
    onRename: (PlaylistSummary) -> Unit,
    onDelete: (PlaylistSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("我的歌单", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("把此刻想听的歌放在一起", color = Muted)
                }
                FilledIconButton(
                    onClick = onCreate,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral, contentColor = Ink),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建歌单")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                MessageState(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    title = "建立第一张歌单",
                    message = "为通勤、运动或某个特别的夜晚收集歌曲",
                    actionLabel = "新建歌单",
                    onAction = onCreate,
                )
            }
        } else {
            itemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
                PlaylistCard(
                    playlist = playlist,
                    colorIndex = index,
                    onClick = { onOpen(playlist) },
                    onRename = { onRename(playlist) },
                    onDelete = { onDelete(playlist) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistSummary,
    colorIndex: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val gradients = listOf(
        listOf(Color(0xFF6B3B35), Color(0xFF2B2024)),
        listOf(Color(0xFF433D72), Color(0xFF22202E)),
        listOf(Color(0xFF315D58), Color(0xFF1D2929)),
        listOf(Color(0xFF69562F), Color(0xFF2D281E)),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(gradients[colorIndex % gradients.size]))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Cream, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text("${playlist.songCount} 首歌曲", color = Cream.copy(alpha = 0.65f))
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "歌单菜单")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除歌单") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Cream.copy(alpha = 0.5f))
    }
}

@Composable
private fun PlaylistDetailScreen(
    title: String,
    songs: List<Song>,
    currentSongId: Long?,
    playbackMode: PlaybackMode,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onRandomPlay: () -> Unit,
    onAddSongs: () -> Unit,
    onRemove: (Song) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF342635), Ink)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .background(
                            Brush.linearGradient(listOf(Coral, Violet)),
                            RoundedCornerShape(30.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Ink, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(5.dp))
                Text("${songs.size} 首歌曲", color = Muted)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            val startIndex = if (playbackMode == PlaybackMode.REVERSE) {
                                songs.lastIndex
                            } else {
                                0
                            }
                            onPlay(startIndex)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("播放")
                    }
                    OutlinedButton(enabled = songs.isNotEmpty(), onClick = onRandomPlay) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("随机播放")
                    }
                    FilledIconButton(onClick = onAddSongs) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加歌曲")
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        if (songs.isEmpty()) {
            item {
                MessageState(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    title = "歌单还是空的",
                    message = "从曲库挑几首歌，组成属于你的播放顺序",
                    actionLabel = "添加歌曲",
                    onAction = onAddSongs,
                )
            }
        } else {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    isPlaying = song.id == currentSongId,
                    onClick = {
                        if (song.id == currentSongId) onOpenPlayer() else onPlay(index)
                    },
                    onAddToPlaylist = null,
                    onRemove = { onRemove(song) },
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    playback: PlaybackUiState,
    onClick: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = Color(0xFF292530),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column {
            val progress = if (playback.durationMs > 0) {
                playback.positionMs.toFloat() / playback.durationMs
            } else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Coral,
                trackColor = Color.Transparent,
            )
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(playback.artworkUri, playback.title, 48.dp, 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playback.title.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playback.artist.ifBlank { "未知歌手" },
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlayback) {
                    Icon(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
                }
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(
    playback: PlaybackUiState,
    lyricsState: LyricsUiState,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlaybackModeChange: (PlaybackMode) -> Unit,
    onImportLyrics: (Long, Uri) -> Unit,
    onDeleteLyrics: () -> Unit,
) {
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val pendingLyricsSongId = rememberPendingLyricsSongId()
    var draggedPosition by remember(playback.mediaId) { mutableFloatStateOf(-1f) }
    val recordRotation = remember(playback.mediaId) {
        val initialAngle = (playback.positionMs % RECORD_ROTATION_DURATION_MS).toFloat() /
            RECORD_ROTATION_DURATION_MS * 360f
        Animatable(initialAngle)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val capturedSongId = pendingLyricsSongId.value
        pendingLyricsSongId.value = null
        lyricsImportRequest(capturedSongId, uri)?.let { request ->
            onImportLyrics(request.songId, request.uri)
        }
    }
    val displayedPosition = if (draggedPosition >= 0f) draggedPosition.toLong() else playback.positionMs

    LaunchedEffect(playback.mediaId, playback.isPlaying) {
        if (playback.isPlaying) {
            while (isActive) {
                val normalizedAngle = ((recordRotation.value % 360f) + 360f) % 360f
                val remainingAngle = if (normalizedAngle < 0.01f) 360f else 360f - normalizedAngle
                val remainingDuration = (
                    RECORD_ROTATION_DURATION_MS * remainingAngle / 360f
                ).roundToInt().coerceAtLeast(1)
                recordRotation.animateTo(
                    targetValue = recordRotation.value + remainingAngle,
                    animationSpec = tween(
                        durationMillis = remainingDuration,
                        easing = LinearEasing,
                    ),
                )
                recordRotation.snapTo(0f)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        contentColor = Cream,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            NowPlayingGradientTop,
                            NowPlayingGradientMiddle,
                            NowPlayingGradientBottom,
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起播放器")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("正在播放", style = MaterialTheme.typography.labelLarge, color = Cream)
                Text(
                    playback.album.ifBlank { "本地音乐" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(48.dp))
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                .padding(3.dp),
        ) {
            PlayerTab(
                label = "封面",
                icon = Icons.Rounded.Album,
                selected = !showLyrics,
                onClick = { showLyrics = false },
            )
            PlayerTab(
                label = "歌词",
                icon = Icons.Rounded.Lyrics,
                selected = showLyrics,
                onClick = { showLyrics = true },
            )
        }

        Crossfade(
            targetState = showLyrics,
            label = "cover_lyrics",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { lyricsVisible ->
            if (lyricsVisible) {
                LyricsPanel(
                    lyricsState = lyricsState,
                    positionMs = playback.positionMs,
                    onSeek = onSeek,
                    onImport = {
                        pendingLyricsSongId.value = playback.mediaId
                        importLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
                    },
                    onDelete = onDeleteLyrics,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    VinylRecord(
                        uri = playback.artworkUri,
                        title = playback.title,
                        isPlaying = playback.isPlaying,
                        rotation = recordRotation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                playback.title.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                playback.artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Slider(
                value = displayedPosition.toFloat().coerceIn(0f, playback.durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { draggedPosition = it },
                onValueChangeFinished = {
                    if (draggedPosition >= 0f) onSeek(draggedPosition.toLong())
                    draggedPosition = -1f
                },
                valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Coral,
                    activeTrackColor = Coral,
                    inactiveTrackColor = PlayerControlSurface,
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(displayedPosition.asDuration(), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(playback.durationMs.asDuration(), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlaybackModeSelector(
                    mode = playback.playbackMode,
                    onModeChange = onPlaybackModeChange,
                )
                FilledIconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, Cream.copy(alpha = 0.18f), CircleShape),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = PlayerControlSurface,
                        contentColor = Cream,
                    ),
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral, contentColor = Ink),
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledIconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, Cream.copy(alpha = 0.18f), CircleShape),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = PlayerControlSurface,
                        contentColor = Cream,
                    ),
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = onStopPlayback,
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, Cream.copy(alpha = 0.18f), CircleShape),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = PlayerControlSurface,
                        contentColor = CoralSoft,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = "停止播放",
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    }
}

@Composable
private fun PlaybackModeSelector(
    mode: PlaybackMode,
    onModeChange: (PlaybackMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledIconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(48.dp)
                .border(1.dp, Cream.copy(alpha = 0.18f), CircleShape),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = PlayerControlSurface,
                contentColor = if (mode == PlaybackMode.SEQUENTIAL) Cream else CoralSoft,
            ),
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = "播放模式：${mode.displayName}",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(270.dp),
        ) {
            Text(
                "选择播放模式",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
            )
            PlaybackMode.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                item.displayName,
                                color = if (item == mode) CoralSoft else Cream,
                                fontWeight = if (item == mode) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                item.description,
                                color = Muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (item == mode) Coral else Muted,
                        )
                    },
                    trailingIcon = if (item == mode) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = Coral) }
                    } else null,
                    onClick = {
                        expanded = false
                        onModeChange(item)
                    },
                )
            }
        }
    }
}

private val PlaybackMode.displayName: String
    get() = when (this) {
        PlaybackMode.SEQUENTIAL -> "顺序播放"
        PlaybackMode.LOOP -> "列表循环"
        PlaybackMode.RANDOM -> "随机播放"
        PlaybackMode.SINGLE -> "单曲循环"
        PlaybackMode.REVERSE -> "倒序播放"
    }

private val PlaybackMode.description: String
    get() = when (this) {
        PlaybackMode.SEQUENTIAL -> "从上到下播放，列表结束后停止"
        PlaybackMode.LOOP -> "从上到下播放，列表结束后从头继续"
        PlaybackMode.RANDOM -> "打乱当前列表的播放顺序"
        PlaybackMode.SINGLE -> "持续循环播放当前歌曲"
        PlaybackMode.REVERSE -> "从列表末尾向前播放，到开头后从末尾继续"
    }

private val PlaybackMode.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        PlaybackMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.QueueMusic
        PlaybackMode.LOOP -> Icons.Rounded.Repeat
        PlaybackMode.RANDOM -> Icons.Rounded.Shuffle
        PlaybackMode.SINGLE -> Icons.Rounded.RepeatOne
        PlaybackMode.REVERSE -> Icons.Rounded.SwapVert
    }

@Composable
private fun PlayerTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(if (selected) Cream else Color.Transparent, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Ink else Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) Ink else Muted, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LyricsPanel(
    lyricsState: LyricsUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    when (lyricsState) {
        LyricsUiState.NoSong, LyricsUiState.Loading -> LoadingState("正在加载歌词…")
        LyricsUiState.Missing -> MessageState(
            icon = Icons.Rounded.Lyrics,
            title = "还没有歌词",
            message = "导入与歌曲对应的 LRC 文件，即可随播放进度高亮滚动",
            actionLabel = "导入 LRC",
            onAction = onImport,
        )
        is LyricsUiState.Error -> MessageState(
            icon = Icons.Rounded.Lyrics,
            title = "歌词无法显示",
            message = lyricsState.message,
            actionLabel = "重新导入",
            onAction = onImport,
        )
        is LyricsUiState.Ready -> SyncedLyrics(
            lyrics = lyricsState.lyrics,
            positionMs = positionMs,
            onSeek = onSeek,
            onImport = onImport,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: ParsedLyrics,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeIndex = lyrics.activeLineIndex(positionMs)
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.isSynced) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isActive = index == activeIndex
                Text(
                    text = line.text,
                    style = if (isActive) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = if (isActive) Cream else Cream.copy(alpha = if (lyrics.isSynced) 0.34f else 0.78f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = line.timeMs != null) {
                            line.timeMs?.let(onSeek)
                            scope.launch { listState.animateScrollToItem((index - 2).coerceAtLeast(0)) }
                        }
                        .padding(vertical = 2.dp),
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.background(Ink.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "歌词菜单")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("更换歌词") },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onImport()
                    },
                )
                DropdownMenuItem(
                    text = { Text("移除歌词") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistPickerDialog(
    song: Song,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onSelect: (PlaylistSummary) -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column {
                Text(
                    song.title,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onCreate,
                    color = Coral.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Coral)
                        Spacer(Modifier.width(10.dp))
                        Text("新建歌单", color = CoralSoft, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.height((playlists.size.coerceAtMost(5) * 58).dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(playlist) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Muted)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${playlist.songCount} 首歌曲", color = Muted, fontSize = 12.sp)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SongPickerDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onSelect: (Song) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(songs, filter) {
        if (filter.isBlank()) songs else songs.filter {
            it.title.contains(filter, true) || it.artist.contains(filter, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加歌曲") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("搜索曲库") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    Text("没有可添加的歌曲", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(modifier = Modifier.height(350.dp)) {
                        items(filtered, key = { it.id }) { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(song) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArtwork(song.albumArtUri, song.title, 42.dp, 12.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                                }
                                Icon(Icons.Rounded.Add, contentDescription = "添加", tint = Coral)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { if (it.length <= 40) value = it },
                label = { Text("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VinylRecord(
    uri: Uri?,
    title: String,
    isPlaying: Boolean,
    rotation: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val needleAngle by animateFloatAsState(
        targetValue = if (isPlaying) 0f else -17f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "tonearm_angle",
    )
    val colorSeed = title.hashCode()
    val artworkColors = ArtworkPalettes[(colorSeed and Int.MAX_VALUE) % ArtworkPalettes.size]

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 唱片、中心封面和轴心作为一个整体旋转。
        Box(
            modifier = Modifier
                .fillMaxSize(0.84f)
                .shadow(
                    elevation = 28.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .graphicsLayer { rotationZ = rotation.value }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color(0xFF343138),
                            0.48f to Color(0xFF151418),
                            0.78f to Color(0xFF08080A),
                            1f to Color(0xFF020203),
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val grooveColor = Color.White.copy(alpha = 0.075f)
                val darkGroove = Color.Black.copy(alpha = 0.46f)
                listOf(0.98f, 0.94f, 0.90f, 0.85f, 0.80f, 0.75f, 0.70f, 0.65f, 0.60f, 0.55f)
                    .forEachIndexed { index, factor ->
                        drawCircle(
                            color = if (index % 2 == 0) grooveColor else darkGroove,
                            radius = radius * factor,
                            style = Stroke(width = if (index % 3 == 0) 1.2.dp.toPx() else 0.65.dp.toPx()),
                        )
                    }
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.09f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.025f),
                            Color.Transparent,
                        )
                    ),
                    radius = radius * 0.96f,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(0.48f)
                    .align(Alignment.Center)
                    .border(4.dp, Color(0xFF08080A), CircleShape)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(artworkColors)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxSize(0.34f),
                )
                if (uri != null) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "$title 唱片封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(Cream.copy(alpha = 0.95f), CircleShape)
                        .border(4.dp, Color(0xFF17151A), CircleShape),
                )
            }
        }

        // 固定的弧形高光让唱片在旋转时仍保持材质层次。
        Canvas(
            modifier = Modifier
                .fillMaxSize(0.84f)
                .align(Alignment.Center),
        ) {
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 205f,
                sweepAngle = 34f,
                useCenter = false,
                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.045f),
                startAngle = 25f,
                sweepAngle = 28f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 播放时唱针落下，暂停时抬回；转轴与唱针本身不随唱片旋转。
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.43f)
                .fillMaxHeight(0.70f)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.78f, 0.11f)
                    rotationZ = needleAngle
                },
        ) {
            val pivot = Offset(size.width * 0.78f, size.height * 0.11f)
            val elbow = Offset(size.width * 0.69f, size.height * 0.30f)
            val stylus = Offset(size.width * 0.25f, size.height * 0.80f)

            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 19.dp.toPx(), center = pivot)
            drawCircle(Color(0xFF343139), radius = 16.dp.toPx(), center = pivot)
            drawCircle(Color(0xFFC9C4CC), radius = 8.dp.toPx(), center = pivot)
            drawCircle(Color(0xFF5D5962), radius = 4.dp.toPx(), center = pivot)

            drawLine(
                color = Color.Black.copy(alpha = 0.48f),
                start = pivot,
                end = stylus,
                strokeWidth = 11.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFBDB8C1),
                start = pivot,
                end = elbow,
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFD7D3DA),
                start = elbow,
                end = stylus,
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(pivot.x - 1.5.dp.toPx(), pivot.y - 1.dp.toPx()),
                end = Offset(stylus.x - 1.5.dp.toPx(), stylus.y - 1.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFF2B272D), radius = 11.dp.toPx(), center = stylus)
            drawCircle(Coral, radius = 3.dp.toPx(), center = stylus)
        }
    }
}

@Composable
private fun AlbumArtwork(
    uri: Uri?,
    title: String,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val colorSeed = title.hashCode()
    val colors = ArtworkPalettes[(colorSeed and Int.MAX_VALUE) % ArtworkPalettes.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(size * 0.42f),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = "$title 专辑封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Coral, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Muted)
    }
}

@Composable
private fun MessageState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SurfaceRaised, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Coral, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(7.dp))
        Text(message, color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
