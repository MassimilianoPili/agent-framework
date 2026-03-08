package com.massimiliano.appmaze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun HomeScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AppMaze - Maze Game")
        Button(
            onClick = { navController.navigate("difficulty") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Play")
        }
        Button(
            onClick = { navController.navigate("leaderboard") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Leaderboard")
        }
        Button(
            onClick = { navController.navigate("settings") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Settings")
        }
    }
}
