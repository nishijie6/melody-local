package com.melody.local.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.melody.local.ui.theme.MelodyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PermissionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explainsLocalOnlyAccessAndRequestsPermission() {
        var requested = false
        composeRule.setContent {
            MelodyTheme {
                PermissionScreen(onRequestPermission = { requested = true })
            }
        }

        composeRule.onNodeWithText("让音乐住进这里").assertIsDisplayed()
        composeRule.onNodeWithText("歌曲只在本机处理，不会上传。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("允许访问音乐").performClick()
        assertTrue(requested)
    }
}
