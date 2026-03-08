package com.massimiliano.appmaze.domain.scoring

import com.massimiliano.appmaze.domain.maze.Difficulty

/**
 * Calculates the final score for a completed maze run.
 *
 * ## Formula
 *
 * ```
 * baseScore    = difficulty.gridSize * difficulty.gridSize * 10
 * timeBonus    = max(0, TIME_BONUS_CAP - elapsedSeconds) * difficulty.multiplier
 * hintPenalty  = hintsUsed * HINT_PENALTY
 * finalScore   = max(0, baseScore + timeBonus - hintPenalty)
 * ```
 *
 * | Difficulty | Multiplier |
 * |------------|-----------|
 * | EASY       | 1         |
 * | MEDIUM     | 2         |
 * | HARD       | 3         |
 * | EXPERT     | 5         |
 */
object ScoreCalculator {

    private const val TIME_BONUS_CAP = 300L   // seconds — no time bonus beyond this
    private const val HINT_PENALTY = 50

    fun calculate(
        difficulty: Difficulty,
        elapsedSeconds: Long,
        hintsUsed: Int
    ): Int {
        val baseScore = difficulty.gridSize * difficulty.gridSize * 10
        val timeBonus = maxOf(0L, TIME_BONUS_CAP - elapsedSeconds) * difficulty.multiplier
        val hintPenalty = hintsUsed * HINT_PENALTY
        return maxOf(0, baseScore + timeBonus.toInt() - hintPenalty)
    }

    private val Difficulty.multiplier: Int
        get() = when (this) {
            Difficulty.EASY   -> 1
            Difficulty.MEDIUM -> 2
            Difficulty.HARD   -> 3
            Difficulty.EXPERT -> 5
        }
}
