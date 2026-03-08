package com.massimiliano.appmaze.domain.scoring

import com.massimiliano.appmaze.domain.maze.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScoreCalculator].
 *
 * Verifies the scoring formula:
 *   baseScore    = gridSize² × 10
 *   timeBonus    = max(0, 300 - elapsedSeconds) × multiplier
 *   hintPenalty  = hintsUsed × 50
 *   finalScore   = max(0, baseScore + timeBonus - hintPenalty)
 */
class ScoreCalculatorTest {

    // ── EASY (10×10, multiplier=1) ──────────────────────────────────────────

    @Test
    fun `easy difficulty base score is 1000`() {
        // 10*10*10 = 1000 base; 0 elapsed → full time bonus = 300*1 = 300; no hints
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 0, hintsUsed = 0)
        assertEquals(1300, score)
    }

    @Test
    fun `easy difficulty with elapsed time reduces time bonus`() {
        // elapsed=100 → timeBonus = (300-100)*1 = 200; base=1000; total=1200
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 100, hintsUsed = 0)
        assertEquals(1200, score)
    }

    @Test
    fun `easy difficulty no time bonus when elapsed exceeds cap`() {
        // elapsed=400 > 300 → timeBonus = 0; base=1000; total=1000
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 400, hintsUsed = 0)
        assertEquals(1000, score)
    }

    @Test
    fun `easy difficulty hint penalty reduces score`() {
        // elapsed=0, hints=2 → 1300 - 100 = 1200
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 0, hintsUsed = 2)
        assertEquals(1200, score)
    }

    // ── MEDIUM (20×20, multiplier=2) ────────────────────────────────────────

    @Test
    fun `medium difficulty base score is 4000`() {
        // 20*20*10 = 4000; elapsed=0 → timeBonus=300*2=600; total=4600
        val score = ScoreCalculator.calculate(Difficulty.MEDIUM, elapsedSeconds = 0, hintsUsed = 0)
        assertEquals(4600, score)
    }

    // ── HARD (30×30, multiplier=3) ──────────────────────────────────────────

    @Test
    fun `hard difficulty base score is 9000`() {
        // 30*30*10 = 9000; elapsed=0 → timeBonus=300*3=900; total=9900
        val score = ScoreCalculator.calculate(Difficulty.HARD, elapsedSeconds = 0, hintsUsed = 0)
        assertEquals(9900, score)
    }

    // ── EXPERT (40×40, multiplier=5) ────────────────────────────────────────

    @Test
    fun `expert difficulty base score is 16000`() {
        // 40*40*10 = 16000; elapsed=0 → timeBonus=300*5=1500; total=17500
        val score = ScoreCalculator.calculate(Difficulty.EXPERT, elapsedSeconds = 0, hintsUsed = 0)
        assertEquals(17500, score)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `score never goes negative with many hints`() {
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 400, hintsUsed = 1000)
        assertTrue("Score must be >= 0", score >= 0)
    }

    @Test
    fun `score is zero when hints exceed base score`() {
        // base=1000, timeBonus=0 (elapsed=400), hintPenalty=1000*50=50000 → clamped to 0
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 400, hintsUsed = 1000)
        assertEquals(0, score)
    }

    @Test
    fun `elapsed exactly at cap gives zero time bonus`() {
        // elapsed=300 → timeBonus = (300-300)*1 = 0; base=1000; total=1000
        val score = ScoreCalculator.calculate(Difficulty.EASY, elapsedSeconds = 300, hintsUsed = 0)
        assertEquals(1000, score)
    }

    @Test
    fun `all difficulty levels produce positive scores with no hints and fast completion`() {
        Difficulty.entries.forEach { difficulty ->
            val score = ScoreCalculator.calculate(difficulty, elapsedSeconds = 10, hintsUsed = 0)
            assertTrue("Score for $difficulty must be positive, was $score", score > 0)
        }
    }
}
