package com.massimiliano.appmaze.domain.scoring

/**
 * Scoring system for the maze game
 */
class ScoringEngine {
    fun calculateScore(
        difficulty: String,
        timeSeconds: Int,
        hintsUsed: Int
    ): Int {
        val baseScore = when (difficulty) {
            "EASY" -> 100
            "MEDIUM" -> 250
            "HARD" -> 500
            "EXTREME" -> 1000
            else -> 100
        }

        // Time bonus: faster completion = higher score
        val timeBonus = maxOf(0, 300 - timeSeconds) / 3

        // Hint penalty: each hint reduces score
        val hintPenalty = hintsUsed * 50

        return maxOf(0, baseScore + timeBonus - hintPenalty)
    }
}
