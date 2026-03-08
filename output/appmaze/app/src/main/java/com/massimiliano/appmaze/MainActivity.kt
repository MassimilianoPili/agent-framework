package com.massimiliano.appmaze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.massimiliano.appmaze.ui.screens.HomeScreen
import com.massimiliano.appmaze.ui.screens.GameScreen
import com.massimiliano.appmaze.ui.screens.DifficultyScreen
import com.massimiliano.appmaze.ui.screens.LeaderboardScreen
import com.massimiliano.appmaze.ui.screens.SettingsScreen
import com.massimiliano.appmaze.ui.theme.AppMazeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppMazeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable("difficulty") {
            DifficultyScreen(navController = navController)
        }
        composable("game/{difficulty}") { backStackEntry ->
            val difficulty = backStackEntry.arguments?.getString("difficulty") ?: "MEDIUM"
            GameScreen(navController = navController, difficulty = difficulty)
        }
        composable("leaderboard") {
            LeaderboardScreen(navController = navController)
        }
        composable("settings") {
            SettingsScreen(navController = navController)
        }
    }
}
