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
fun GameScreen(navController: NavController, difficulty: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Screen - Difficulty: $difficulty")
        Text("Maze rendering and gameplay will be implemented here")
        Button(
            onClick = { navController.popBackStack() },
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Back")
        }
    }
}
