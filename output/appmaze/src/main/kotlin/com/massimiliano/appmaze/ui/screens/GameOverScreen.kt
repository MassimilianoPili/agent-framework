package com.massimiliano.appmaze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Game Over/Results Screen.
 * Displays final score, time taken, and difficulty.
 * Allows player to play again, return home, or view leaderboard.
 *
 * @param score The final score achieved
 * @param timeSeconds Time taken to complete the maze in seconds
 * @param difficulty The difficulty level that was played
 * @param onPlayAgainClick Callback to play again with same difficulty
 * @param onHomeClick Callback to return to home screen
 * @param onLeaderboardClick Callback to view leaderboard
 */
@Composable
fun GameOverScreen(
    score: Int,
    timeSeconds: Int,
    difficulty: String,
    onPlayAgainClick: () -> Unit,
    onHomeClick: () -> Unit,
    onLeaderboardClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Game Over Title
        Text(
            text = "Maze Complete!",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Score Display
        Text(
            text = "Score",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Time Display
        Text(
            text = "Time",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = formatTime(timeSeconds),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Difficulty Display
        Text(
            text = "Difficulty: $difficulty",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Play Again Button
        Button(
            onClick = onPlayAgainClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .height(56.dp)
        ) {
            Text("Play Again")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Leaderboard Button
        Button(
            onClick = onLeaderboardClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .height(56.dp)
        ) {
            Text("Leaderboard")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Home Button
        Button(
            onClick = onHomeClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .height(56.dp)
        ) {
            Text("Home")
        }
    }
}

/**
 * Formats seconds into MM:SS format.
 */
private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
