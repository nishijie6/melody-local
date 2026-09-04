package com.melody.local.lyrics

import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.Song
import com.melody.local.lyrics.discovery.LyricsSearchRequest
import com.melody.local.lyrics.discovery.OnlineLyricsSource
import com.melody.local.lyrics.discovery.RemoteLyricsResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsResolverInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun contentResolverLookupReadsOpenableDisplayName() = runBlocking {
        var projectionSeen = emptyList<String>()
        val lookup = ContentResolverLyricsSourceFileNameLookup { _, projection ->
            projectionSeen = projection.toList()
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf("01.mp3"))
            }
        }

        val fileName = lookup.find(Uri.parse("content://yinlan.lyrics.test/audio/7"))

        assertEquals("01.mp3", fileName)
        assertEquals(listOf(OpenableColumns.DISPLAY_NAME), projectionSeen)
    }

    @Test
    fun resolverUsesAudioDisplayNameForAuthorizedFolderExactMatch() = runBlocking {
        val preferences = LyricsAutomationPreferences(context)
        val previous = preferences.get()
        val folderUri = Uri.parse("content://lyrics/tree/root")
        val lyricUri = Uri.parse("content://lyrics/document/01.lrc")
        val store = RecordingLyricsStore()
        try {
            preferences.update {
                it.copy(
                    searchAuthorizedFolders = false,
                    readEmbeddedLyrics = false,
                    automaticOnlineLookup = false,
                    folderUris = emptySet(),
                )
            }
            val authorizedSource = AuthorizedFolderLyricsSource(
                scanner = AuthorizedLyricsCandidateScanner { folders ->
                    assertEquals(listOf(folderUri), folders.toList())
                    listOf(
                        AuthorizedLyricDocument(
                            displayName = "01.lrc",
                            uri = lyricUri,
                            sizeBytes = 24L,
                            folderUri = folderUri,
                        )
                    )
                },
                settingsProvider = {
                    LyricsAutomationSettings(
                        searchAuthorizedFolders = true,
                        readEmbeddedLyrics = false,
                        automaticOnlineLookup = false,
                        folderUris = setOf(folderUri.toString()),
                    )
                },
            )
            val resolver = LyricsResolver(
                context = context,
                store = store,
                preferences = preferences,
                embeddedExtractor = object : EmbeddedLyricsExtractor {
                    override suspend fun extract(uri: Uri): EmbeddedLyrics? = null
                },
                onlineSource = noResultsOnlineSource(),
                extraLocalSources = listOf(authorizedSource),
                sourceFileNameLookup = LyricsSourceFileNameLookup { "01.mp3" },
            )

            val resolution = resolver.resolve(song(title = "歌曲名"), allowOnline = false)

            assertTrue(resolution is LyricsResolution.Applied)
            assertEquals(lyricUri, store.importedUri)
        } finally {
            preferences.update { previous }
        }
    }

    @Test
    fun automaticAndManualOnlineSearchUseTheSameResolvedDisplayName() = runBlocking {
        val preferences = LyricsAutomationPreferences(context)
        val previous = preferences.get()
        val seenFileNames = mutableListOf<String?>()
        try {
            preferences.update {
                it.copy(
                    searchAuthorizedFolders = false,
                    readEmbeddedLyrics = false,
                    automaticOnlineLookup = true,
                    folderUris = emptySet(),
                )
            }
            val resolver = LyricsResolver(
                context = context,
                store = RecordingLyricsStore(),
                preferences = preferences,
                embeddedExtractor = object : EmbeddedLyricsExtractor {
                    override suspend fun extract(uri: Uri): EmbeddedLyrics? = null
                },
                onlineSource = object : OnlineLyricsSource {
                    override suspend fun search(request: LyricsSearchRequest) =
                        RemoteLyricsResult.NoResults.also {
                            seenFileNames += request.track.sourceFileName
                        }

                    override suspend fun download(recordId: Long) = RemoteLyricsResult.NoResults
                },
                sourceFileNameLookup = LyricsSourceFileNameLookup { "01.mp3" },
            )
            val song = song(title = "歌曲名")

            resolver.resolve(song, allowOnline = true)
            resolver.searchOnline(song, keywords = null)

            assertEquals(listOf("01.mp3", "01.mp3"), seenFileNames)
        } finally {
            preferences.update { previous }
        }
    }

    private class RecordingLyricsStore : LyricsStore {
        var importedUri: Uri? = null
        private var lyrics: ParsedLyrics? = null

        override suspend fun load(songId: Long): ParsedLyrics? = lyrics

        override suspend fun import(songId: Long, uri: Uri): ParsedLyrics {
            importedUri = uri
            return ParsedLyrics(listOf(LyricLine(0L, "歌词")), isSynced = true)
                .also { lyrics = it }
        }

        override suspend fun delete(songId: Long) = Unit
        override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
    }

    private fun noResultsOnlineSource() = object : OnlineLyricsSource {
        override suspend fun search(request: LyricsSearchRequest) = RemoteLyricsResult.NoResults
        override suspend fun download(recordId: Long) = RemoteLyricsResult.NoResults
    }

    private fun song(title: String) = Song(
        id = 7L,
        title = title,
        artist = "歌手",
        album = "专辑",
        albumId = 0L,
        durationMs = 180_000L,
        trackNumber = 1,
        dateAddedSeconds = 0L,
        contentUri = Uri.parse("content://media/external/audio/media/7"),
        albumArtUri = null,
    )
}
