package com.beauty.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Guards against Compose artifacts drifting apart: a Material version older than foundation supplies
 * a legacy `Indication` through `LocalIndication`, and every plain `Modifier.clickable` then throws.
 */
class BeautyThemeIndicationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun plainClickableRendersAndClicksInsideTheme() {
        var clicks = 0
        composeRule.setContent {
            BeautyTheme { Box(Modifier.clickable { clicks++ }) { Text("Tap") } }
        }

        composeRule.onNodeWithText("Tap").performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
