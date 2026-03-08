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
 * Home/Splash Screen - Starting point of the app.
 * Displays game title, welcome message, and navigation buttons.
 *
 * @param onPlayClick Callback when Play button is clicked
 * @param onLeaderboardClick Callback when Leaderboard button is clicked
 * @param onContinueClick Callback when Continue button is clicked (optional, only shown if saved game exists)
 * @param hasSavedGame Whether a saved game exists that can be resumed
 */
@Composable
fun HomeScreen(
    onPlayClick: () -> Unit,
    onLeaderboardClick: () -> Unit,
    onContinueClick: (() -> Unit)? = null,
    hasSavedGame: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Game Title
        Text(
            text = "AppMaze",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitle
        Text(
            text = "Navigate the procedurally generated maze",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // New Game Button
        Button(
            onClick = onPlayClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .height(56.dp)
        ) {
            Text(
                text = "New Game",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Continue Button (only shown if saved game exists)
        if (hasSavedGame && onContinueClick != null) {
            Button(
                onClick = onContinueClick,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .height(56.dp)
            ) {
                Text(
                    text = "Continue",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Leaderboard Button
        Button(
            onClick = onLeaderboardClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .height(56.dp)
        ) {
            Text(
                text = "Leaderboard",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
