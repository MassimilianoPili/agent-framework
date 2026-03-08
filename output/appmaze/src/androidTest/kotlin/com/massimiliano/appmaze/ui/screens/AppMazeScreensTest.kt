package com.massimiliano.appmaze.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for AppMaze screens.
 * Verifies that all screens render correctly and respond to user interactions.
 */
@RunWith(AndroidJUnit4::class)
class AppMazeScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ==================== HomeScreen Tests ====================

    @Test
    fun homeScreen_displaysTitle() {
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("AppMaze").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysNewGameButton() {
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("New Game").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysLeaderboardButton() {
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Leaderboard").assertIsDisplayed()
    }

    @Test
    fun homeScreen_newGameButtonClickable() {
        var playClicked = false
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = { playClicked = true },
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("New Game").performClick()
        assert(playClicked)
    }

    @Test
    fun homeScreen_leaderboardButtonClickable() {
        var leaderboardClicked = false
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = { leaderboardClicked = true }
            )
        }
        composeTestRule.onNodeWithText("Leaderboard").performClick()
        assert(leaderboardClicked)
    }

    @Test
    fun homeScreen_continueButtonShownWhenSavedGameExists() {
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = {},
                onContinueClick = {},
                hasSavedGame = true
            )
        }
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun homeScreen_continueButtonHiddenWhenNoSavedGame() {
        composeTestRule.setContent {
            HomeScreen(
                onPlayClick = {},
                onLeaderboardClick = {},
                hasSavedGame = false
            )
        }
        composeTestRule.onNodeWithText("Continue").assertDoesNotExist()
    }

    // ==================== DifficultySelectionScreen Tests ====================

    @Test
    fun difficultyScreen_displaysTitle() {
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = {},
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Select Difficulty").assertIsDisplayed()
    }

    @Test
    fun difficultyScreen_displaysAllDifficulties() {
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = {},
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Easy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Medium").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Expert").assertIsDisplayed()
    }

    @Test
    fun difficultyScreen_easyButtonClickable() {
        var selectedDifficulty = ""
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = { selectedDifficulty = it },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Easy").performClick()
        assert(selectedDifficulty == "EASY")
    }

    @Test
    fun difficultyScreen_mediumButtonClickable() {
        var selectedDifficulty = ""
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = { selectedDifficulty = it },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Medium").performClick()
        assert(selectedDifficulty == "MEDIUM")
    }

    @Test
    fun difficultyScreen_hardButtonClickable() {
        var selectedDifficulty = ""
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = { selectedDifficulty = it },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Hard").performClick()
        assert(selectedDifficulty == "HARD")
    }

    @Test
    fun difficultyScreen_expertButtonClickable() {
        var selectedDifficulty = ""
        composeTestRule.setContent {
            DifficultySelectionScreen(
                onDifficultySelected = { selectedDifficulty = it },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Expert").performClick()
        assert(selectedDifficulty == "EXPERT")
    }

    // ==================== GameScreen Tests ====================

    @Test
    fun gameScreen_displaysTitle() {
        composeTestRule.setContent {
            GameScreen(
                difficulty = "MEDIUM",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Maze - MEDIUM").assertIsDisplayed()
    }

    @Test
    fun gameScreen_displaysGameStats() {
        composeTestRule.setContent {
            GameScreen(
                difficulty = "HARD",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Time").assertIsDisplayed()
        composeTestRule.onNodeWithText("Score").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hints").assertIsDisplayed()
    }

    @Test
    fun gameScreen_displaysControlButtons() {
        composeTestRule.setContent {
            GameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Hint").assertIsDisplayed()
    }

    // ==================== GameOverScreen Tests ====================

    @Test
    fun gameOverScreen_displaysTitle() {
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = {},
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Maze Complete!").assertIsDisplayed()
    }

    @Test
    fun gameOverScreen_displaysScore() {
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = {},
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("1250").assertIsDisplayed()
    }

    @Test
    fun gameOverScreen_displaysTime() {
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = {},
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("00:45").assertIsDisplayed()
    }

    @Test
    fun gameOverScreen_displaysDifficulty() {
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "HARD",
                onPlayAgainClick = {},
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Difficulty: HARD").assertIsDisplayed()
    }

    @Test
    fun gameOverScreen_displaysNavigationButtons() {
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = {},
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Play Again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Leaderboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun gameOverScreen_playAgainButtonClickable() {
        var playAgainClicked = false
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = { playAgainClicked = true },
                onHomeClick = {},
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Play Again").performClick()
        assert(playAgainClicked)
    }

    @Test
    fun gameOverScreen_homeButtonClickable() {
        var homeClicked = false
        composeTestRule.setContent {
            GameOverScreen(
                score = 1250,
                timeSeconds = 45,
                difficulty = "MEDIUM",
                onPlayAgainClick = {},
                onHomeClick = { homeClicked = true },
                onLeaderboardClick = {}
            )
        }
        composeTestRule.onNodeWithText("Home").performClick()
        assert(homeClicked)
    }

    // ==================== LeaderboardScreen Tests ====================

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
}
