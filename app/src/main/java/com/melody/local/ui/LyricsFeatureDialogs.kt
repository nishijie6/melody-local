package com.melody.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melody.local.lyrics.LyricsAutomationSettings
import com.melody.local.lyrics.discovery.RankedOnlineLyrics
import com.melody.local.systemlyrics.AudioOutputRoute

@Composable
internal fun LyricsSearchDialog(
    state: LyricsSearchUiState,
    onSearch: (String?) -> Unit,
    onSelect: (RankedOnlineLyrics) -> Unit,
    onDismiss: () -> Unit,
) {
    var keywords by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线歌词") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        label = { Text("关键词（留空按歌曲信息搜索）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { onSearch(keywords.trim().ifBlank { null }) }) {
                        Text("搜索")
                    }
                }
                when (state) {
                    LyricsSearchUiState.Idle -> Unit
                    LyricsSearchUiState.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                    }
                    is LyricsSearchUiState.Error -> {
                        Text(state.message)
                        Text("可修改关键词重试；自动匹配失败不会覆盖已有本地歌词。")
                    }
                    is LyricsSearchUiState.Results -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(state.matches, key = { it.record.id }) { match ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(match) }
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(match.record.trackName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf(match.record.artistName, match.record.albumName)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · ")
                                )
                                Text(
                                    "匹配度 ${match.score}% · " +
                                        if (match.record.isSynced) "同步歌词" else "纯文本歌词"
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun LyricsSettingsDialog(
    automation: LyricsAutomationSettings,
    outputRoute: AudioOutputRoute,
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    overlayEnabled: Boolean,
    notificationLyricsEnabled: Boolean,
    automaticLatencyEnabled: Boolean,
    manualDelayMs: Long,
    appliedDelayMs: Long,
    onSelectFolder: () -> Unit,
    onClearFolders: () -> Unit,
    onSearchFoldersChange: (Boolean) -> Unit,
    onEmbeddedChange: (Boolean) -> Unit,
    onAutomaticOnlineChange: (Boolean) -> Unit,
    onOverlayChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onAutomaticLatencyChange: (Boolean) -> Unit,
    onManualDelayChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var delayText by remember(outputRoute, manualDelayMs) { mutableStateOf(manualDelayMs.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌词设置") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SettingSwitch("自动匹配已授权目录", automation.searchAuthorizedFolders, onSearchFoldersChange) }
                item { SettingSwitch("自动读取歌曲内嵌歌词", automation.readEmbeddedLyrics, onEmbeddedChange) }
                item {
                    SettingSwitch("自动在线匹配", automation.automaticOnlineLookup, onAutomaticOnlineChange)
                    Text("开启后会把歌曲标题、歌手和专辑发送给 LRCLIB；已有本地歌词不会上传或覆盖。")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSelectFolder) { Text("授权歌词目录") }
                        if (automation.folderUris.isNotEmpty()) {
                            OutlinedButton(onClick = onClearFolders) { Text("清除目录") }
                        }
                    }
                    Text("已授权 ${automation.folderUris.size} 个目录；仅扫描其中的 LRC 文件。")
                }
                item { HorizontalDivider(); Spacer(Modifier.height(2.dp)) }
                item {
                    SettingSwitch(
                        "桌面悬浮歌词",
                        overlayEnabled && overlayPermissionGranted,
                        onOverlayChange,
                    )
                    if (!overlayPermissionGranted) Text("开启时会进入 Android 系统页面授予悬浮窗权限。")
                }
                item { SettingSwitch("通知与锁屏显示当前歌词", notificationLyricsEnabled, onNotificationChange) }
                if (!notificationPermissionGranted) {
                    item { Text("Android 通知权限尚未授予；开启该项时会请求系统授权。") }
                }
                item { SettingSwitch("自动补偿输出延迟", automaticLatencyEnabled, onAutomaticLatencyChange) }
                item {
                    Text("当前输出：${outputRoute.displayName()} · 实际补偿 ${appliedDelayMs} ms")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = {
                                delayText = it.filter { character -> character == '-' || character.isDigit() }
                            },
                            label = { Text("当前设备手动微调 ms") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { delayText.toLongOrNull()?.let(onManualDelayChange) },
                        ) { Text("应用") }
                    }
                    Text("范围 -5000…5000 ms；正数让歌词更晚出现，负数让歌词提前。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun AudioOutputRoute.displayName(): String = when (this) {
    AudioOutputRoute.SPEAKER -> "扬声器"
    AudioOutputRoute.WIRED -> "有线耳机"
    AudioOutputRoute.BLUETOOTH_CLASSIC -> "传统蓝牙"
    AudioOutputRoute.BLUETOOTH_LE -> "蓝牙 LE"
    AudioOutputRoute.USB -> "USB 音频"
    AudioOutputRoute.HDMI -> "HDMI"
    AudioOutputRoute.UNKNOWN -> "未知设备"
}
