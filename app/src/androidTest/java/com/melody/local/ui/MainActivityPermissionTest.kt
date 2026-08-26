package com.melody.local.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.rule.GrantPermissionRule
import com.melody.local.MainActivity
import org.junit.Rule
import org.junit.Test

class MainActivityPermissionTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun grantedPermissionOpensTheLibraryInsteadOfThePermissionScreen() {
        composeRule.onNodeWithText("我的音乐").assertIsDisplayed()
    }
}
