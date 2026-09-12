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

@Composable
internal fun SongMetadataEditorDialog(
    song: Song,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Long, String, String, String, Uri?) -> Unit,
) {
    var title by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var album by rememberSaveable(song.id) { mutableStateOf(song.album) }
    var artwork by rememberSaveable(song.id) { mutableStateOf<String?>(null) }
    var pickerSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (pickerSongId == song.id && uri != null) artwork = uri.toString()
        pickerSongId = null
    }
    val valid = SongMetadataConstraints.fieldsAreValid(title, artist, album)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑歌曲信息") },
        text = {
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("修改仅在音澜内生效，音频文件和歌单关联保持不变。")
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
                if (!valid) Text(
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && valid,
                onClick = { onSave(song.id, title, artist, album, artwork?.let(Uri::parse)) },
                modifier = Modifier.testTag("song-edit-save"),
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}
