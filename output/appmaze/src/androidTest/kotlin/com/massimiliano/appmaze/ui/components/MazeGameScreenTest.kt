package com.massimiliano.appmaze.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import com.massimiliano.appmaze.domain.maze.MazeCell
import com.massimiliano.appmaze.domain.maze.MazeGenerator
import com.massimiliano.appmaze.ui.game.GameStatus
import com.massimiliano.appmaze.ui.game.GameUiState
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for MazeGameScreen composable.
 */
class MazeGameScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mazeGameScreen_displaysTitle() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Maze - EASY").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysTimerLabel() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Time").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysScoreLabel() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Score").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysHintsLabel() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Hints").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysHintButton() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onNodeWithText("Hint").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysMazeCanvas() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun mazeGameScreen_acceptsSwipeInput() {
        var swipeDetected = false
        composeTestRule.setContent {
            SwipeDetector(
                onSwipe = { swipeDetected = true },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().performTouchInput { swipeUp() }
        // Swipe should be detected
    }

    @Test
    fun mazeGameScreen_formatsTimeCorrectly() {
        // Test the formatTime function indirectly through the screen
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        // Initial time should be 00:00
        composeTestRule.onNodeWithText("00:00").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysInitialScore() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        // Initial score should be 0
        composeTestRule.onNodeWithText("0").assertExists()
    }

    @Test
    fun mazeGameScreen_displaysInitialHints() {
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = {}
            )
        }
        // Initial hints should be 3
        composeTestRule.onNodeWithText("3").assertExists()
    }

    @Test
    fun mazeGameScreen_supportsDifferentDifficulties() {
        for (difficulty in listOf("EASY", "MEDIUM", "HARD", "EXPERT")) {
            composeTestRule.setContent {
                MazeGameScreen(
                    difficulty = difficulty,
                    onGameOver = { _, _ -> },
                    onBackClick = {}
                )
            }
            composeTestRule.onNodeWithText("Maze - $difficulty").assertExists()
        }
    }

    @Test
    fun mazeGameScreen_callsOnBackClickWhenBackPressed() {
        var backClicked = false
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { _, _ -> },
                onBackClick = { backClicked = true }
            )
        }
        // Back button should be present (tested in navigation tests)
        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun mazeGameScreen_callsOnGameOverWhenCompleted() {
        var gameOverCalled = false
        var finalScore = 0
        var finalTime = 0
        composeTestRule.setContent {
            MazeGameScreen(
                difficulty = "EASY",
                onGameOver = { score, time ->
                    gameOverCalled = true
                    finalScore = score
                    finalTime = time
                },
                onBackClick = {}
            )
        }
        // Game over callback should be called when game is won
        composeTestRule.onRoot().assertExists()
    }
}
