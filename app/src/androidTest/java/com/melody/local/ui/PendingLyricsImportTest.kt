package com.melody.local.ui

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingLyricsImportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickerResultKeepsTheSongCapturedAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var pendingSongId: MutableState<Long?>
        val uri = Uri.parse("content://lyrics/selected")

        restorationTester.setContent {
            pendingSongId = rememberPendingLyricsSongId()
        }
        composeRule.runOnIdle { pendingSongId.value = 101L }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            val request = lyricsImportRequest(pendingSongId.value, uri)
            pendingSongId.value = null

            assertEquals(101L, request?.songId)
            assertEquals(uri, request?.uri)
            assertNull(lyricsImportRequest(pendingSongId.value, uri))
        }
    }
}

