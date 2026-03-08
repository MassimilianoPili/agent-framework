package com.massimiliano.appmaze.domain.game

import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("ScoringEngine Tests")
class ScoringEngineTest {

    @Test
    @DisplayName("EASY difficulty: base score 1000")
    fun easyBaseScore() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EASY, 0, 0)
        assertEquals(1000, score)
    }

    @Test
    @DisplayName("MEDIUM difficulty: base score 2000")
    fun mediumBaseScore() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.MEDIUM, 0, 0)
        assertEquals(2000, score)
    }

    @Test
    @DisplayName("HARD difficulty: base score 3000")
    fun hardBaseScore() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.HARD, 0, 0)
        assertEquals(3000, score)
    }

    @Test
    @DisplayName("EXPERT difficulty: base score 5000")
    fun expertBaseScore() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EXPERT, 0, 0)
        assertEquals(5000, score)
    }

    @Test
    @DisplayName("Time penalty: 1 point per second")
    fun timePenalty() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EASY, 100, 0)
        assertEquals(900, score)  // 1000 - 100
    }

    @Test
    @DisplayName("Hint penalty: 100 points per hint")
    fun hintPenalty() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EASY, 0, 3)
        assertEquals(700, score)  // 1000 - (3 * 100)
    }

    @Test
    @DisplayName("Combined penalties: time + hints")
    fun combinedPenalties() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.MEDIUM, 50, 2)
        assertEquals(1850, score)  // 2000 - 50 - (2 * 100)
    }

    @Test
    @DisplayName("Score never goes below 0")
    fun minimumScoreZero() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EASY, 2000, 10)
        assertEquals(0, score)  // 1000 - 2000 - 1000 = -2000, clamped to 0
    }

    @Test
    @DisplayName("Calculate score using difficulty string")
    fun calculateScoreWithString() {
        val score = ScoringEngine.calculateScore("HARD", 100, 1)
        assertEquals(2800, score)  // 3000 - 100 - 100
    }

    @Test
    @DisplayName("Get base score for difficulty")
    fun getBaseScore() {
        assertEquals(1000, ScoringEngine.getBaseScore(DifficultyLevel.EASY))
        assertEquals(2000, ScoringEngine.getBaseScore(DifficultyLevel.MEDIUM))
        assertEquals(3000, ScoringEngine.getBaseScore(DifficultyLevel.HARD))
        assertEquals(5000, ScoringEngine.getBaseScore(DifficultyLevel.EXPERT))
    }

    @Test
    @DisplayName("Get difficulty multiplier")
    fun getDifficultyMultiplier() {
        assertEquals(1.0, ScoringEngine.getDifficultyMultiplier(DifficultyLevel.EASY))
        assertEquals(2.0, ScoringEngine.getDifficultyMultiplier(DifficultyLevel.MEDIUM))
        assertEquals(3.0, ScoringEngine.getDifficultyMultiplier(DifficultyLevel.HARD))
        assertEquals(5.0, ScoringEngine.getDifficultyMultiplier(DifficultyLevel.EXPERT))
    }

    @Test
    @DisplayName("Calculate time penalty")
    fun calculateTimePenalty() {
        assertEquals(0, ScoringEngine.calculateTimePenalty(0))
        assertEquals(60, ScoringEngine.calculateTimePenalty(60))
        assertEquals(300, ScoringEngine.calculateTimePenalty(300))
    }

    @Test
    @DisplayName("Calculate hint penalty")
    fun calculateHintPenalty() {
        assertEquals(0, ScoringEngine.calculateHintPenalty(0))
        assertEquals(100, ScoringEngine.calculateHintPenalty(1))
        assertEquals(500, ScoringEngine.calculateHintPenalty(5))
    }

    @Test
    @DisplayName("Real-world scenario: MEDIUM, 120 seconds, 2 hints")
    fun realWorldScenario() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.MEDIUM, 120, 2)
        assertEquals(1680, score)  // 2000 - 120 - 200
    }

    @Test
    @DisplayName("Real-world scenario: EXPERT, 300 seconds, 0 hints")
    fun expertFastCompletion() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EXPERT, 300, 0)
        assertEquals(4700, score)  // 5000 - 300
    }

    @Test
    @DisplayName("Real-world scenario: EASY, 45 seconds, 1 hint")
    fun easyWithHint() {
        val score = ScoringEngine.calculateScore(DifficultyLevel.EASY, 45, 1)
        assertEquals(855, score)  // 1000 - 45 - 100
    }
}
