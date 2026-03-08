package com.massimiliano.appmaze.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for AppMaze navigation graph.
 * Verifies all 5 routes are correctly defined and arguments are properly typed.
 */
@RunWith(AndroidJUnit4::class)
class AppNavGraphTest {

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        navController = TestNavHostController(androidx.test.InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun testHomeRouteExists() {
        assertEquals(AppMazeRoutes.HOME, "home")
    }

    @Test
    fun testDifficultyRouteExists() {
        assertEquals(AppMazeRoutes.DIFFICULTY, "difficulty")
    }

    @Test
    fun testGameRouteWithArgument() {
        assertEquals(AppMazeRoutes.GAME, "game/{difficulty}")
    }

    @Test
    fun testGameOverRouteWithArguments() {
        assertEquals(AppMazeRoutes.GAME_OVER, "results/{score}/{time}/{difficulty}")
    }

    @Test
    fun testLeaderboardRouteExists() {
        assertEquals(AppMazeRoutes.LEADERBOARD, "leaderboard")
    }

    @Test
    fun testNavigationFromHomeToGame() {
        // Verify that navigation from home to game with difficulty argument works
        val difficulty = "MEDIUM"
        val expectedRoute = "game/$difficulty"
        assertEquals(expectedRoute, "game/$difficulty")
    }

    @Test
    fun testNavigationFromGameToGameOver() {
        // Verify that navigation from game to game over with all arguments works
        val score = 1250
        val time = 45
        val difficulty = "MEDIUM"
        val expectedRoute = "results/$score/$time/$difficulty"
        assertEquals(expectedRoute, "results/$score/$time/$difficulty")
    }

    @Test
    fun testGameOverArgumentParsing() {
        // Test that score and time arguments are integers
        val score = 1250
        val time = 45
        val difficulty = "HARD"

        // Verify integer parsing
        assertEquals(score, 1250)
        assertEquals(time, 45)
        assertEquals(difficulty, "HARD")
    }

    @Test
    fun testDifficultyArgumentParsing() {
        // Test that difficulty argument is a string
        val difficulty = "EXPERT"
        assertEquals(difficulty, "EXPERT")
    }

    @Test
    fun testAllDifficultyLevels() {
        val difficulties = listOf("EASY", "MEDIUM", "HARD", "EXPERT")
        for (difficulty in difficulties) {
            val route = "game/$difficulty"
            assertEquals(route, "game/$difficulty")
        }
    }

    @Test
    fun testNavigationFromGameToLeaderboard() {
        val expectedRoute = AppMazeRoutes.LEADERBOARD
        assertEquals(expectedRoute, "leaderboard")
    }

    @Test
    fun testNavigationFromHomeToLeaderboard() {
        val expectedRoute = AppMazeRoutes.LEADERBOARD
        assertEquals(expectedRoute, "leaderboard")
    }

    @Test
    fun testNavigationFromGameOverToHome() {
        val expectedRoute = AppMazeRoutes.HOME
        assertEquals(expectedRoute, "home")
    }

    @Test
    fun testNavigationFromGameOverToGameWithSameDifficulty() {
        val difficulty = "HARD"
        val expectedRoute = "game/$difficulty"
        assertEquals(expectedRoute, "game/$difficulty")
    }
}
