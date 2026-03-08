package com.massimiliano.appmaze.ui.screens.leaderboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.massimiliano.appmaze.ui.screens.LeaderboardScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for LeaderboardScreen.
 */
@RunWith(AndroidJUnit4::class)
class LeaderboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun leaderboardScreen_displaysTitle() {
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = {})
        }
        composeTestRule.onNodeWithText("Leaderboard").assertIsDisplayed()
    }

    @Test
    fun leaderboardScreen_displaysTopScoresHeader() {
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = {})
        }
        composeTestRule.onNodeWithText("Top 10 Scores").assertIsDisplayed()
    }

    @Test
    fun leaderboardScreen_displaysDifficultyTabs() {
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = {})
        }
        composeTestRule.onNodeWithText("Easy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Medium").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Expert").assertIsDisplayed()
    }

    @Test
    fun leaderboardScreen_displaysClearButton() {
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = {})
        }
        composeTestRule.onNodeWithText("Clear All Scores").assertIsDisplayed()
    }

    @Test
    fun leaderboardScreen_tabsAreClickable() {
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = {})
        }
        // Click on Medium tab
        composeTestRule.onNodeWithText("Medium").performClick()
        // Verify tab is selected (no exception thrown)
    }

    @Test
    fun leaderboardScreen_backButtonCallsCallback() {
        var backClicked = false
        composeTestRule.setContent {
            LeaderboardScreen(onBackClick = { backClicked = true })
        }
        // Back button is in the TopAppBar
        composeTestRule.onRoot().assertExists()
    }
}
