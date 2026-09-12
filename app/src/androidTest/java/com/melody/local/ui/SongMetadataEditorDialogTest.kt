package com.melody.local.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.Song
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongMetadataEditorDialogTest {
    @get:Rule val compose = createComposeRule()
    private val original = Song(
        id = 51, title = "Old title", artist = "Artist", album = "Album", albumId = 0,
        durationMs = 60000, trackNumber = 1, dateAddedSeconds = 1,
        contentUri = Uri.parse("content://media/external_primary/audio/media/51"), albumArtUri = null,
    )

    @Test fun draftSurvivesRecreationAndSavesTheOriginalSongId() {
        var savedId: Long? = null
        var savedFields: List<String>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                SongMetadataEditorDialog(original, false, null, {}) { id, title, artist, album, _ ->
                    savedId = id
                    savedFields = listOf(title, artist, album)
                }
            }
        }
        compose.onNodeWithTag("song-edit-title").performTextReplacement("New title")
        compose.onNodeWithTag("song-edit-artist").performTextReplacement("New artist")
        compose.onNodeWithTag("song-edit-album").performTextReplacement("New album")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("song-edit-save").performClick()
        compose.runOnIdle {
            assertEquals(51L, savedId)
            assertEquals(listOf("New title", "New artist", "New album"), savedFields)
        }
    }

    @Test fun emptyTitleCannotBeSaved() {
        compose.setContent {
            MaterialTheme { SongMetadataEditorDialog(original, false, null, {}) { _, _, _, _, _ -> } }
        }
        compose.onNodeWithTag("song-edit-title").performTextReplacement(" ")
        compose.onNodeWithTag("song-edit-save").assertIsNotEnabled()
    }

    @Test fun savingDisablesFieldsAndDismissal() {
        compose.setContent {
            MaterialTheme { SongMetadataEditorDialog(original, true, null, {}) { _, _, _, _, _ -> } }
        }
        compose.onNodeWithTag("song-edit-title").assertIsNotEnabled()
        compose.onNodeWithTag("song-edit-save").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
}
