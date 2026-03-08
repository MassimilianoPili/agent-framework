package com.massimiliano.appmaze.ui.screens.leaderboard

import com.massimiliano.appmaze.data.db.entity.GameScoreEntity
import com.massimiliano.appmaze.data.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("LeaderboardViewModel Tests")
class LeaderboardViewModelTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var viewModel: LeaderboardViewModel

    @BeforeEach
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        viewModel = LeaderboardViewModel(gameRepository)
    }

    @Test
    @DisplayName("Load scores initializes all difficulty score lists")
    fun loadScoresInitializesAllDifficulties() = runTest {
        val easyScores = listOf(
            GameScoreEntity(1, "EASY", 60, 1000, 0, System.currentTimeMillis()),
            GameScoreEntity(2, "EASY", 90, 800, 0, System.currentTimeMillis())
        )
        val mediumScores = listOf(
            GameScoreEntity(3, "MEDIUM", 120, 2000, 0, System.currentTimeMillis())
        )
        val hardScores = listOf(
            GameScoreEntity(4, "HARD", 180, 3000, 0, System.currentTimeMillis())
        )
        val expertScores = listOf(
            GameScoreEntity(5, "EXPERT", 240, 5000, 0, System.currentTimeMillis())
        )

        coEvery { gameRepository.getTopScoresByDifficulty("EASY", 10) } returns easyScores
        coEvery { gameRepository.getTopScoresByDifficulty("MEDIUM", 10) } returns mediumScores
        coEvery { gameRepository.getTopScoresByDifficulty("HARD", 10) } returns hardScores
        coEvery { gameRepository.getTopScoresByDifficulty("EXPERT", 10) } returns expertScores

        viewModel.loadScores()

        // Give coroutine time to complete
        kotlinx.coroutines.delay(100)

        assertEquals(easyScores, viewModel.easyScores.value)
        assertEquals(mediumScores, viewModel.mediumScores.value)
        assertEquals(hardScores, viewModel.hardScores.value)
        assertEquals(expertScores, viewModel.expertScores.value)
    }

    @Test
    @DisplayName("Load scores with empty results")
    fun loadScoresWithEmptyResults() = runTest {
        coEvery { gameRepository.getTopScoresByDifficulty("EASY", 10) } returns emptyList()
        coEvery { gameRepository.getTopScoresByDifficulty("MEDIUM", 10) } returns emptyList()
        coEvery { gameRepository.getTopScoresByDifficulty("HARD", 10) } returns emptyList()
        coEvery { gameRepository.getTopScoresByDifficulty("EXPERT", 10) } returns emptyList()

        viewModel.loadScores()

        kotlinx.coroutines.delay(100)

        assertTrue(viewModel.easyScores.value.isEmpty())
        assertTrue(viewModel.mediumScores.value.isEmpty())
        assertTrue(viewModel.hardScores.value.isEmpty())
        assertTrue(viewModel.expertScores.value.isEmpty())
    }

    @Test
    @DisplayName("Clear all scores clears all lists")
    fun clearAllScoresClearsAllLists() = runTest {
        coEvery { gameRepository.deleteAllScores() } returns Unit

        viewModel.clearAllScores()

        kotlinx.coroutines.delay(100)

        assertTrue(viewModel.easyScores.value.isEmpty())
        assertTrue(viewModel.mediumScores.value.isEmpty())
        assertTrue(viewModel.hardScores.value.isEmpty())
        assertTrue(viewModel.expertScores.value.isEmpty())

        coVerify { gameRepository.deleteAllScores() }
    }

    @Test
    @DisplayName("Load scores respects limit parameter")
    fun loadScoresRespectsLimit() = runTest {
        val scores = (1..10).map {
            GameScoreEntity(it.toLong(), "EASY", 60 + it, 1000 - it * 10, 0, System.currentTimeMillis())
        }

        coEvery { gameRepository.getTopScoresByDifficulty("EASY", 10) } returns scores
        coEvery { gameRepository.getTopScoresByDifficulty("MEDIUM", 10) } returns emptyList()
        coEvery { gameRepository.getTopScoresByDifficulty("HARD", 10) } returns emptyList()
        coEvery { gameRepository.getTopScoresByDifficulty("EXPERT", 10) } returns emptyList()

        viewModel.loadScores()

        kotlinx.coroutines.delay(100)

        assertEquals(10, viewModel.easyScores.value.size)
    }
}
