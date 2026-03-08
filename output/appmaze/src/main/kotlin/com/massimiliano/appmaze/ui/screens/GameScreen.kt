package com.massimiliano.appmaze.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.hiltViewModel
import com.massimiliano.appmaze.ui.components.MazeGameScreen
import com.massimiliano.appmaze.ui.game.GameViewModel

/**
 * Game Screen - Main gameplay area.
 * Displays the maze, player position, timer, score, and hint button.
 * Handles swipe input for player movement.
 *
 * @param difficulty The selected difficulty level (EASY, MEDIUM, HARD, EXPERT)
 * @param onGameOver Callback when game is completed with score and time
 * @param onBackClick Callback when back button is clicked
 * @param viewModel GameViewModel instance (injected via Hilt)
 */
@Composable
fun GameScreen(
    difficulty: String,
    onGameOver: (score: Int, timeSeconds: Int) -> Unit,
    onBackClick: () -> Unit,
    viewModel: GameViewModel = hiltViewModel(),
) {
    MazeGameScreen(
        difficulty = difficulty,
        onGameOver = onGameOver,
        onBackClick = onBackClick,
        viewModel = viewModel
    )
}
