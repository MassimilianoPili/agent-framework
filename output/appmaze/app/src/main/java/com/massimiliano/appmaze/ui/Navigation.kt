package com.massimiliano.appmaze.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.massimiliano.appmaze.ui.screens.GameScreen
import com.massimiliano.appmaze.ui.screens.HomeScreen
import com.massimiliano.appmaze.ui.screens.LeaderboardScreen
import com.massimiliano.appmaze.ui.screens.SelectDifficultyScreen
import com.massimiliano.appmaze.ui.screens.SettingsScreen

/**
 * Sealed class representing all navigation destinations in the app.
 *
 * The 5 required screens are:
 *  1. Home          — main menu / landing screen
 *  2. SelectDifficulty — choose difficulty level before starting a game
 *  3. Game          — the actual maze game screen
 *  4. Leaderboard   — high scores / game history from Room DB
 *  5. Settings      — sound, haptic, theme preferences
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object SelectDifficulty : Screen("select_difficulty")
    data object Game : Screen("game/{difficulty}") {
        fun createRoute(difficulty: String) = "game/$difficulty"
    }
    data object Leaderboard : Screen("leaderboard")
    data object Settings : Screen("settings")
}

/**
 * Root navigation host. Wires all 5 screens together.
 */
@Composable
fun AppMazeNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onPlayClicked = { navController.navigate(Screen.SelectDifficulty.route) },
                onLeaderboardClicked = { navController.navigate(Screen.Leaderboard.route) },
                onSettingsClicked = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.SelectDifficulty.route) {
            SelectDifficultyScreen(
                onDifficultySelected = { difficulty ->
                    navController.navigate(Screen.Game.createRoute(difficulty))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Game.route,
            arguments = listOf(
                navArgument("difficulty") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val difficulty = backStackEntry.arguments?.getString("difficulty") ?: "EASY"
            GameScreen(
                difficulty = difficulty,
                onGameFinished = { navController.navigate(Screen.Leaderboard.route) {
                    popUpTo(Screen.Home.route)
                }},
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Leaderboard.route) {
            LeaderboardScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
