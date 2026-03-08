package com.massimiliano.appmaze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.hiltViewModel
import com.massimiliano.appmaze.data.db.entity.GameScoreEntity
import com.massimiliano.appmaze.ui.screens.leaderboard.LeaderboardViewModel

/**
 * Leaderboard/High Scores Screen.
 * Displays top scores from the database, tabbed by difficulty level.
 * Supports clearing all scores.
 *
 * @param onBackClick Callback when back button is clicked
 * @param viewModel LeaderboardViewModel instance (injected via Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    onBackClick: () -> Unit,
    viewModel: LeaderboardViewModel = hiltViewModel(),
) {
    val easyScores by viewModel.easyScores.collectAsState(initial = emptyList())
    val mediumScores by viewModel.mediumScores.collectAsState(initial = emptyList())
    val hardScores by viewModel.hardScores.collectAsState(initial = emptyList())
    val expertScores by viewModel.expertScores.collectAsState(initial = emptyList())

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Load scores on composition
    LaunchedEffect(Unit) {
        viewModel.loadScores()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Scores?") },
            text = { Text("This action cannot be undone. All leaderboard scores will be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllScores()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leaderboard") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            // Difficulty tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Easy") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Medium") }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Hard") }
                )
                Tab(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    text = { Text("Expert") }
                )
            }

            // Scores list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Top 10 Scores",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val scores = when (selectedTabIndex) {
                    0 -> easyScores
                    1 -> mediumScores
                    2 -> hardScores
                    3 -> expertScores
                    else -> emptyList()
                }

                if (scores.isEmpty()) {
                    Text(
                        text = "No scores yet. Play a game to get on the leaderboard!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(scores) { index, score ->
                            LeaderboardItemCard(
                                rank = index + 1,
                                score = score,
                                isTopThree = index < 3
                            )
                        }
                    }
                }
            }

            // Clear All button
            Button(
                onClick = { showClearDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Clear All Scores")
            }
        }
    }
}

@Composable
private fun LeaderboardItemCard(
    rank: Int,
    score: GameScoreEntity,
    isTopThree: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTopThree) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )

            // Score Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Score: ${score.score}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Time: ${formatTime(score.timeSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Date
            Text(
                text = formatDate(score.completedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
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

/**
 * Formats timestamp into readable date format (MM/DD/YYYY).
 */
private fun formatDate(timestampMs: Long): String {
    val date = java.util.Date(timestampMs)
    val format = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US)
    return format.format(date)
}
