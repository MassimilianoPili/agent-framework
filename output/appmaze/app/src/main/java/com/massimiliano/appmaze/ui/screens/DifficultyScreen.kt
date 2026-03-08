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
fun DifficultyScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Select Difficulty")
        Button(
            onClick = { navController.navigate("game/EASY") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Easy (10x10)")
        }
        Button(
            onClick = { navController.navigate("game/MEDIUM") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Medium (20x20)")
        }
        Button(
            onClick = { navController.navigate("game/HARD") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Hard (30x30)")
        }
        Button(
            onClick = { navController.navigate("game/EXTREME") },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Extreme (40x40)")
        }
        Button(
            onClick = { navController.popBackStack() },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Back")
        }
    }
}
