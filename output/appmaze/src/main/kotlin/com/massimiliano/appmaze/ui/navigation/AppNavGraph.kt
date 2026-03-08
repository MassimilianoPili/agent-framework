package com.massimiliano.appmaze.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.massimiliano.appmaze.ui.screens.DifficultySelectionScreen
import com.massimiliano.appmaze.ui.screens.GameOverScreen
import com.massimiliano.appmaze.ui.screens.GameScreen
import com.massimiliano.appmaze.ui.screens.HomeScreen
import com.massimiliano.appmaze.ui.screens.LeaderboardScreen

/**
 * Navigation routes for AppMaze.
 * Defines all screen destinations and their route patterns.
 */
object AppMazeRoutes {
    const val HOME = "home"
    const val DIFFICULTY = "difficulty"
    const val GAME = "game/{difficulty}"
    const val GAME_OVER = "results/{score}/{time}/{difficulty}"
    const val LEADERBOARD = "leaderboard"
}

/**
 * AppMaze Navigation Graph.
 *
 * Defines all 5 screens and their navigation routes:
 * 1. Home/Splash - Starting screen
 * 2. Difficulty Selection - Choose game difficulty (10x10, 20x20, 30x30, 40x40)
 * 3. Game - Main game screen with maze rendering and swipe input
 * 4. Game Over/Results - Display final score, time, and difficulty
 * 5. Leaderboard/High Scores - Display top scores from database
 *
 * @param navController The navigation controller managing screen transitions
 */
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppMazeRoutes.HOME
    ) {
        // Home/Splash Screen
        composable(AppMazeRoutes.HOME) {
            HomeScreen(
                onPlayClick = {
                    navController.navigate(AppMazeRoutes.DIFFICULTY) {
                        popUpTo(AppMazeRoutes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onLeaderboardClick = {
                    navController.navigate(AppMazeRoutes.LEADERBOARD) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // Difficulty Selection Screen
        composable(AppMazeRoutes.DIFFICULTY) {
            DifficultySelectionScreen(
                onDifficultySelected = { difficulty ->
                    navController.navigate("game/$difficulty") {
                        popUpTo(AppMazeRoutes.DIFFICULTY) { saveState = true }
                        launchSingleTop = true
                    }
                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Game Screen
        composable(
            route = AppMazeRoutes.GAME,
            arguments = listOf(
                navArgument("difficulty") {
                    type = NavType.StringType
                    nullable = false
                }
            )
        ) { backStackEntry ->
            val difficulty = backStackEntry.arguments?.getString("difficulty") ?: "MEDIUM"
            GameScreen(
                difficulty = difficulty,
                onGameOver = { score, timeSeconds ->
                    navController.navigate("results/$score/$timeSeconds/$difficulty") {
                        popUpTo(AppMazeRoutes.GAME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Game Over/Results Screen
        composable(
            route = AppMazeRoutes.GAME_OVER,
            arguments = listOf(
                navArgument("score") {
                    type = NavType.IntType
                    nullable = false
                },
                navArgument("time") {
                    type = NavType.IntType
                    nullable = false
                },
                navArgument("difficulty") {
                    type = NavType.StringType
                    nullable = false
                }
            )
        ) { backStackEntry ->
            val score = backStackEntry.arguments?.getInt("score") ?: 0
            val time = backStackEntry.arguments?.getInt("time") ?: 0
            val difficulty = backStackEntry.arguments?.getString("difficulty") ?: "MEDIUM"

            GameOverScreen(
                score = score,
                timeSeconds = time,
                difficulty = difficulty,
                onPlayAgainClick = {
                    navController.navigate("game/$difficulty") {
                        popUpTo(AppMazeRoutes.HOME) { saveState = true }
                        launchSingleTop = true
                    }
                },
                onHomeClick = {
                    navController.navigate(AppMazeRoutes.HOME) {
                        popUpTo(AppMazeRoutes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onLeaderboardClick = {
                    navController.navigate(AppMazeRoutes.LEADERBOARD) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // Leaderboard/High Scores Screen
        composable(AppMazeRoutes.LEADERBOARD) {
            LeaderboardScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
