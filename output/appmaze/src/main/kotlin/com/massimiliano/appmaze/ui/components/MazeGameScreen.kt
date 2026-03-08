package com.massimiliano.appmaze.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.hiltViewModel
import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import com.massimiliano.appmaze.ui.game.GameStatus
import com.massimiliano.appmaze.ui.game.GameViewModel

/**
 * Main game screen combining MazeCanvas + SwipeDetector + HUD overlay.
 *
 * Features:
 * - Displays maze using MazeCanvas
 * - Detects swipe input using SwipeDetector
 * - Shows HUD overlay with timer, score, hint button, pause button
 * - Handles game state (playing, paused, won)
 * - Navigates to game over screen on completion
 *
 * @param difficulty The selected difficulty level as a string
 * @param onGameOver Callback when game is completed with score and time
 * @param onBackClick Callback when back button is clicked
 * @param viewModel GameViewModel instance (injected via Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MazeGameScreen(
    difficulty: String,
    onGameOver: (score: Int, timeSeconds: Int) -> Unit,
    onBackClick: () -> Unit,
    viewModel: GameViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Initialize game on first composition
    androidx.compose.runtime.LaunchedEffect(difficulty) {
        val difficultyLevel = DifficultyLevel.valueOf(difficulty)
        viewModel.startNewGame(difficultyLevel)
    }

    // Navigate to game over when game is won
    androidx.compose.runtime.LaunchedEffect(uiState.gameStatus) {
        if (uiState.gameStatus == GameStatus.WON) {
            onGameOver(uiState.score, uiState.elapsedSeconds)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maze - $difficulty") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = androidx.compose.material.icons.automirrored.filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        SwipeDetector(
            onSwipe = { direction ->
                val directionInt = when (direction) {
                    SwipeDirection.UP -> 0
                    SwipeDirection.RIGHT -> 1
                    SwipeDirection.DOWN -> 2
                    SwipeDirection.LEFT -> 3
                }
                viewModel.processSwipe(directionInt)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Game Stats Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Timer
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = formatTime(uiState.elapsedSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Score
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Score",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = uiState.score.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    // Hints Remaining
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Hints",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = uiState.hintsRemaining.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                // Maze Canvas Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (uiState.maze.isNotEmpty()) {
                        MazeCanvas(
                            maze = uiState.maze,
                            playerRow = uiState.playerRow,
                            playerCol = uiState.playerCol,
                            hintPathCells = uiState.hintPathCells,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "Loading maze...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

                // Control Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Hint Button
                    Button(
                        onClick = { viewModel.requestHint() },
                        enabled = uiState.hintsRemaining > 0 && uiState.gameStatus == GameStatus.PLAYING,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = "Hint",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Hint")
                    }

                    // Pause/Resume Button
                    IconButton(
                        onClick = {
                            if (uiState.gameStatus == GameStatus.PLAYING) {
                                viewModel.pauseGame()
                            } else if (uiState.gameStatus == GameStatus.PAUSED) {
                                viewModel.resumeGame()
                            }
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.gameStatus == GameStatus.PLAYING) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (uiState.gameStatus == GameStatus.PLAYING) "Pause" else "Resume"
                        )
                    }
                }

                // Error message display
                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Formats elapsed seconds into MM:SS format.
 */
private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
