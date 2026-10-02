package com.melody.local.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.melody.local.data.Song
import com.melody.local.data.SongMetadataConstraints
import com.melody.local.data.SongPlaybackRange
import com.melody.local.data.SongPlaybackRangeConstraints
import com.melody.local.data.asDuration
import com.melody.local.data.persistedEndMs
import com.melody.local.data.playbackRange
import kotlin.math.roundToLong

@Composable
internal fun SongMetadataEditorDialog(
    song: Song,
    currentSourcePositionMs: Long?,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Long, String, String, String, Uri?, Long, Long?) -> Unit,
) {
    var title by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var album by rememberSaveable(song.id) { mutableStateOf(song.album) }
    var artwork by rememberSaveable(song.id) { mutableStateOf<String?>(null) }
    var pickerSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    val initialPlaybackRange = song.playbackRange()
    var playbackStartMs by rememberSaveable(song.id) {
        mutableStateOf(initialPlaybackRange.startMs)
    }
    var playbackEndMs by rememberSaveable(song.id) {
        mutableStateOf(initialPlaybackRange.endMs)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (pickerSongId == song.id && uri != null) artwork = uri.toString()
        pickerSongId = null
    }
    val metadataValid = SongMetadataConstraints.fieldsAreValid(title, artist, album)
    val playbackRangeValid = SongPlaybackRangeConstraints.isValid(
        sourceDurationMs = song.durationMs,
        startMs = playbackStartMs,
        endMs = playbackEndMs,
    )
    val valid = metadataValid && playbackRangeValid
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑歌曲") },
        text = {
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("修改仅在音澜内生效，原音频文件和歌单关联保持不变。")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    isError = title.isBlank() ||
                        title.trim().length > SongMetadataConstraints.MAX_TEXT_LENGTH,
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("song-edit-title"),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("歌手") },
                    isError = artist.trim().length > SongMetadataConstraints.MAX_TEXT_LENGTH,
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("song-edit-artist"),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("专辑") },
                    isError = album.trim().length > SongMetadataConstraints.MAX_TEXT_LENGTH,
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("song-edit-album"),
                )
                if (!metadataValid) Text(
                    "标题不能为空，每项最多 ${SongMetadataConstraints.MAX_TEXT_LENGTH} 个字符。",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlbumArtwork(
                        uri = artwork?.let(Uri::parse) ?: song.albumArtUri,
                        title = song.title,
                        size = 72.dp,
                        corner = 18.dp,
                    )
                    Column {
                        OutlinedButton(
                            enabled = !saving,
                            onClick = {
                                pickerSongId = song.id
                                picker.launch(arrayOf("image/*"))
                            },
                        ) { Text("更换封面") }
                        if (artwork != null) TextButton(
                            enabled = !saving,
                            onClick = { artwork = null },
                        ) { Text("保留原封面") }
                    }
                }
                Text(
                    "封面会保存到应用内，所选图片最大 ${SongMetadataConstraints.MAX_ARTWORK_MIB} MiB。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("播放裁剪", style = MaterialTheme.typography.titleMedium)
                Text(
                    "拖动两端选择要保留的内容。播放会跳过范围外的前奏和结尾，文件本身不会被截取；保存正在播放的歌曲后会从新起点重新播放。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (song.durationMs > 0L) {
                    Text(
                        "保留 ${playbackStartMs.asDuration()} – ${playbackEndMs.asDuration()}" +
                            "（${(playbackEndMs - playbackStartMs).asDuration()}）",
                        modifier = Modifier.testTag("song-edit-trim-summary"),
                    )
                    RangeSlider(
                        value = playbackStartMs.toFloat()..playbackEndMs.toFloat(),
                        onValueChange = { values ->
                            val candidateStartMs = values.start.roundToLong()
                            val candidateEndMs = values.endInclusive.roundToLong()
                            if (
                                SongPlaybackRangeConstraints.isValid(
                                    sourceDurationMs = song.durationMs,
                                    startMs = candidateStartMs,
                                    endMs = candidateEndMs,
                                )
                            ) {
                                playbackStartMs = candidateStartMs
                                playbackEndMs = candidateEndMs
                            }
                        },
                        valueRange = 0f..song.durationMs.toFloat(),
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().testTag("song-edit-trim-range"),
                    )
                    currentSourcePositionMs?.let { currentPositionMs ->
                        val minimumDurationMs = SongPlaybackRangeConstraints
                            .minimumRetainedDurationMs(song.durationMs)
                        val usableCurrentPositionMs = currentPositionMs.coerceIn(
                            0L,
                            (playbackEndMs - minimumDurationMs).coerceAtLeast(0L),
                        )
                        OutlinedButton(
                            enabled = !saving,
                            onClick = { playbackStartMs = usableCurrentPositionMs },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("song-edit-use-current-position"),
                        ) {
                            Text("使用当前播放位置 ${usableCurrentPositionMs.asDuration()} 作为起点")
                        }
                    }
                    TextButton(
                        enabled = !saving &&
                            (playbackStartMs != 0L || playbackEndMs != song.durationMs),
                        onClick = {
                            playbackStartMs = 0L
                            playbackEndMs = song.durationMs
                        },
                        modifier = Modifier.testTag("song-edit-reset-trim"),
                    ) { Text("恢复完整歌曲") }
                }
                if (!playbackRangeValid) {
                    Text(
                        "裁剪后至少需要保留 1 秒可播放内容。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && valid,
                onClick = {
                    val playbackRange = SongPlaybackRange(playbackStartMs, playbackEndMs)
                    onSave(
                        song.id,
                        title,
                        artist,
                        album,
                        artwork?.let(Uri::parse),
                        playbackRange.startMs,
                        playbackRange.persistedEndMs(song.durationMs),
                    )
                },
                modifier = Modifier.testTag("song-edit-save"),
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}
