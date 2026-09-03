package com.melody.local.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface EmbeddedLyricsExtractor {
    suspend fun extract(uri: Uri): EmbeddedLyrics?
}

/**
 * Extracts lyrics without requesting broad storage access. Android does not currently define a
 * public `METADATA_KEY_LYRIC`; an OEM/future platform field is used when present, then the bounded
 * binary parser handles ID3v2 USLT/SYLT/TXXX, FLAC Vorbis comments and MP4/M4A `©lyr` atoms.
 */
class AndroidEmbeddedLyricsExtractor(
    private val context: Context,
) : EmbeddedLyricsExtractor {
    override suspend fun extract(uri: Uri): EmbeddedLyrics? = withContext(Dispatchers.IO) {
        readPlatformMetadata(uri) ?: runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                EmbeddedLyricsBinaryParser.parse(stream)
            }
        }.getOrNull()
    }

    private fun readPlatformMetadata(uri: Uri): EmbeddedLyrics? {
        val keys = PLATFORM_LYRIC_FIELD_NAMES.mapNotNull { fieldName ->
            runCatching {
                MediaMetadataRetriever::class.java.getField(fieldName).getInt(null)
            }.getOrNull()
        }.distinct()
        if (keys.isEmpty()) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            keys.firstNotNullOfOrNull { key ->
                retriever.extractMetadata(key)
                    ?.replace("\u0000", "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_EMBEDDED_LYRIC_CHARACTERS }
                    ?.let { EmbeddedLyrics(it, EmbeddedLyricsSource.PLATFORM_METADATA) }
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        val PLATFORM_LYRIC_FIELD_NAMES = listOf("METADATA_KEY_LYRIC", "METADATA_KEY_LYRICS")
        const val MAX_EMBEDDED_LYRIC_CHARACTERS = 2 * 1024 * 1024
    }
}
