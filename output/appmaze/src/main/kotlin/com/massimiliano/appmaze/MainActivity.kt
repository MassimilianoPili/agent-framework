package com.massimiliano.appmaze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.massimiliano.appmaze.ui.navigation.AppNavGraph
import com.massimiliano.appmaze.ui.theme.AppMazeTheme

/**
 * MainActivity - Entry point for the AppMaze application.
 * 
 * Sets up:
 * - Material 3 theme with dark mode as default
 * - Jetpack Compose Navigation with 5 screens
 * - Navigation controller for screen transitions
 * 
 * The app launches with the Home screen and supports navigation to:
 * 1. Difficulty Selection
 * 2. Game Screen
 * 3. Game Over/Results
 * 4. Leaderboard
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppMazeTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.massimiliano.appmaze.ui.theme.DarkBackground
                ) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }
}
