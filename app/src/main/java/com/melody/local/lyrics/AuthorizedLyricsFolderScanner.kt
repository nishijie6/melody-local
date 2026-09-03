package com.melody.local.lyrics

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import com.melody.local.lyrics.discovery.LocalLyricsCandidate
import com.melody.local.lyrics.discovery.LocalLyricsLookup
import com.melody.local.lyrics.discovery.LocalLyricsSource
import com.melody.local.lyrics.discovery.LyricsTrack
import com.melody.local.lyrics.discovery.rankedLookup

data class AuthorizedLyricDocument(
    val displayName: String,
    val uri: Uri,
    val sizeBytes: Long?,
    val folderUri: Uri,
)

/**
 * Scans only directories the user explicitly selected with ACTION_OPEN_DOCUMENT_TREE.
 * This keeps automatic sidecar matching compatible with scoped storage without broad
 * storage-management permissions.
 */
class AuthorizedLyricsFolderScanner(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun listCandidates(folderUris: Collection<Uri>): List<AuthorizedLyricDocument> =
        withContext(Dispatchers.IO) {
            buildList {
                for (treeUri in folderUris.distinct()) {
                    if (size >= MAX_RESULTS) break
                    runCatching { scanTree(treeUri, this) }
                }
            }
        }

    private fun scanTree(treeUri: Uri, destination: MutableList<AuthorizedLyricDocument>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<Node>().apply { add(Node(rootId, 0)) }
        var visited = 0

        while (pending.isNotEmpty() && visited < MAX_VISITED_DOCUMENTS && destination.size < MAX_RESULTS) {
            val node = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext() && visited < MAX_VISITED_DOCUMENTS) {
                    visited++
                    val documentId = cursor.getString(idColumn)
                    val displayName = cursor.getString(nameColumn).orEmpty()
                    val mimeType = cursor.getString(mimeColumn).orEmpty()
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (node.depth < MAX_DEPTH) pending.add(Node(documentId, node.depth + 1))
                    } else if (displayName.isLyricFileName()) {
                        val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            cursor.getLong(sizeColumn)
                        } else {
                            null
                        }
                        if (size == null || size in 1..MAX_LYRIC_SIZE_BYTES) {
                            destination += AuthorizedLyricDocument(
                                displayName = displayName,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                sizeBytes = size,
                                folderUri = treeUri,
                            )
                        }
                    }
                    if (destination.size >= MAX_RESULTS) break
                }
            }
        }
    }

    private fun String.isLyricFileName(): Boolean {
        val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension == "lrc" || extension == "elrc" || extension == "txt"
    }

    private data class Node(val documentId: String, val depth: Int)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        // Each persisted tree represents an explicitly selected song directory. Do not recurse
        // into unrelated subfolders, which could silently match a same-named song elsewhere.
        const val MAX_DEPTH = 0
        const val MAX_VISITED_DOCUMENTS = 20_000
        const val MAX_RESULTS = 5_000
        const val MAX_LYRIC_SIZE_BYTES = 2L * 1024L * 1024L
    }
}

class AuthorizedFolderLyricsSource(
    private val scanner: AuthorizedLyricsFolderScanner,
    private val preferences: LyricsAutomationPreferences,
) : LocalLyricsSource {
    override suspend fun find(track: LyricsTrack): LocalLyricsLookup {
        val settings = preferences.get()
        if (!settings.searchAuthorizedFolders) {
            return LocalLyricsLookup.Unavailable("已关闭授权目录自动匹配")
        }
        if (settings.folderUris.isEmpty()) {
            return LocalLyricsLookup.Unavailable("尚未授权歌词目录")
        }
        val candidates = scanner.listCandidates(settings.folderUris.map(Uri::parse)).map { document ->
            LocalLyricsCandidate(
                location = document.uri.toString(),
                displayName = document.displayName,
                sizeBytes = document.sizeBytes,
            )
        }
        return rankedLookup(track, candidates)
    }
}
