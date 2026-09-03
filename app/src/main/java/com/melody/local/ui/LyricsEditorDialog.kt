package com.melody.local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.melody.local.lyrics.LyricsTimelineEditor

@Composable
internal fun LyricsEditorDialog(
    initialText: String,
    playbackPositionMs: Long,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialText) { mutableStateOf(TextFieldValue(initialText)) }
    var shiftText by remember { mutableStateOf("0") }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑歌词与时间轴") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "支持 [分:秒.百分秒] 行时间、<分:秒.百分秒> 逐字时间；" +
                        "同一时间的后续行会作为翻译或罗马音显示。",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val result = LyricsTimelineEditor.insertTimestamp(
                                text = value.text,
                                cursor = value.selection.start,
                                positionMs = playbackPositionMs,
                            )
                            value = TextFieldValue(
                                text = result.text,
                                selection = androidx.compose.ui.text.TextRange(result.cursor),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("插入当前时间")
                    }
                    OutlinedTextField(
                        value = shiftText,
                        onValueChange = { shiftText = it.filter { char -> char == '-' || char.isDigit() } },
                        label = { Text("整轴偏移 ms") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val delta = shiftText.toLongOrNull()
                            if (delta == null) {
                                validationMessage = "请输入有效的毫秒数"
                            } else {
                                value = value.copy(text = LyricsTimelineEditor.shiftAll(value.text, delta))
                                validationMessage = null
                            }
                        },
                    ) {
                        Text("应用")
                    }
                }
                validationMessage?.let { Text(it) }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        validationMessage = null
                    },
                    label = { Text("LRC 内容") },
                    placeholder = { Text("[00:12.30]第一句歌词") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.text.isBlank()) {
                        validationMessage = "歌词内容不能为空"
                    } else {
                        onSave(value.text)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
