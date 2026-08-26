package com.melody.local.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface AudioMediaQuery {
    fun query(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        sortOrder: String,
    ): Cursor?
}

fun interface MusicLibrary {
    suspend fun loadSongs(): List<Song>
}

class MusicRepository internal constructor(
    private val audioMediaQuery: AudioMediaQuery,
) : MusicLibrary {
    constructor(context: Context) : this(
        AudioMediaQuery { collection, projection, selection, sortOrder ->
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
        }
    )

    override suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        audioMediaQuery.query(collection, projection, selection, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val title = cursor.getString(titleColumn).cleanMetadata("未知歌曲")
                val artist = cursor.getString(artistColumn).cleanMetadata("未知歌手")
                val album = cursor.getString(albumColumn).cleanMetadata("未知专辑")
                songs += Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    durationMs = cursor.getLong(durationColumn),
                    trackNumber = cursor.getInt(trackColumn) % 1_000,
                    dateAddedSeconds = cursor.getLong(dateAddedColumn),
                    contentUri = ContentUris.withAppendedId(collection, id),
                    albumArtUri = albumId.takeIf { it > 0 }?.let {
                        ContentUris.withAppendedId(ALBUM_ART_URI, it)
                    },
                )
            }
        }
        songs
    }

    private fun String?.cleanMetadata(fallback: String): String =
        this?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING } ?: fallback

    private companion object {
        val ALBUM_ART_URI = "content://media/external/audio/albumart".toUri()
    }
}
