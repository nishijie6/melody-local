package com.melody.local.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
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
    private val metadataStore: SongMetadataStore = EmptySongMetadataStore,
) : MusicLibrary {
    constructor(
        context: Context,
        metadataStore: SongMetadataStore = EmptySongMetadataStore,
    ) : this(
        AudioMediaQuery { collection, projection, selection, sortOrder ->
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
        },
        metadataStore,
    )

    override suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val overrides = metadataStore.getAll()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()
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
            val volumeNameColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val override = overrides[id]
                val title = override?.title
                    ?: cursor.getString(titleColumn).cleanMetadata("未知歌曲")
                val artist = override?.artist
                    ?: cursor.getString(artistColumn).cleanMetadata("未知歌手")
                val album = override?.album
                    ?: cursor.getString(albumColumn).cleanMetadata("未知专辑")
                val itemCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    volumeNameColumn >= 0
                ) {
                    cursor.getString(volumeNameColumn)?.takeIf(String::isNotBlank)
                        ?.let(MediaStore.Audio.Media::getContentUri)
                        ?: collection
                } else {
                    collection
                }
                songs += Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    durationMs = cursor.getLong(durationColumn),
                    trackNumber = cursor.getInt(trackColumn) % 1_000,
                    dateAddedSeconds = cursor.getLong(dateAddedColumn),
                    // VOLUME_EXTERNAL is a read-only merged view. Keep the concrete volume in the
                    // item URI so later write/delete consent always targets the exact source row.
                    contentUri = ContentUris.withAppendedId(itemCollection, id),
                    albumArtUri = override?.artworkPath?.let(Uri::parse)
                        ?: albumId.takeIf { it > 0 }?.let {
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
