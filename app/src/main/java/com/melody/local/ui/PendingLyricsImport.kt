package com.melody.local.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

internal data class LyricsImportRequest(
    val songId: Long,
    val uri: Uri,
)

@Composable
internal fun rememberPendingLyricsSongId(): MutableState<Long?> =
    rememberSaveable { mutableStateOf(null) }

internal fun lyricsImportRequest(songId: Long?, uri: Uri?): LyricsImportRequest? =
    if (songId != null && uri != null) LyricsImportRequest(songId, uri) else null

