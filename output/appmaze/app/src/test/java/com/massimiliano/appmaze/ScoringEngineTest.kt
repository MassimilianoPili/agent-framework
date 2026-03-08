package com.massimiliano.appmaze

import org.junit.Test
import org.junit.Assert.*

class ScoringEngineTest {
    private val scoringEngine = com.massimiliano.appmaze.domain.scoring.ScoringEngine()

    @Test
    fun testBaseScoringByDifficulty() {
        val easyScore = scoringEngine.calculateScore("EASY", 300, 0)
        val mediumScore = scoringEngine.calculateScore("MEDIUM", 300, 0)
        val hardScore = scoringEngine.calculateScore("HARD", 300, 0)
        val extremeScore = scoringEngine.calculateScore("EXTREME", 300, 0)

        assertTrue(easyScore < mediumScore)
        assertTrue(mediumScore < hardScore)
        assertTrue(hardScore < extremeScore)
    }

    @Test
    fun testTimeBonus() {
        val fastScore = scoringEngine.calculateScore("MEDIUM", 100, 0)
        val slowScore = scoringEngine.calculateScore("MEDIUM", 300, 0)
        assertTrue(fastScore > slowScore)
    }

    @Test
    fun testHintPenalty() {
        val noHintsScore = scoringEngine.calculateScore("MEDIUM", 300, 0)
        val withHintsScore = scoringEngine.calculateScore("MEDIUM", 300, 3)
        assertTrue(noHintsScore > withHintsScore)
    }

    @Test
    fun testMinimumScore() {
        val score = scoringEngine.calculateScore("EASY", 1000, 100)
        assertTrue(score >= 0)
    }
}
