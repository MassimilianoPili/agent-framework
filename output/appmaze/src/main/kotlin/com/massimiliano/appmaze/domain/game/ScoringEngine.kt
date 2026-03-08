package com.massimiliano.appmaze.domain.game

import com.massimiliano.appmaze.domain.maze.DifficultyLevel

/**
 * Calcola il punteggio finale con semantica celebrativa (ispirata a Celeste/Gris).
 *
 * Formula:
 *   score = base + swiftBonus + explorerBonus - hintsUsed * 80
 *   floor = base / 4  (il giocatore riceve sempre almeno il 25% — mai zero)
 *
 * swiftBonus:     fino al +60% del base se si completa sotto il tempo target
 * explorerBonus:  fino al +20% del base se si usa un numero di mosse vicino all'ottimale
 *
 * Filosofia: ogni completamento è una vittoria. I bonus celebrano la velocità e l'eleganza
 * senza penalizzare l'esplorazione. Un giocatore lento vince lo stesso con dignità.
 *
 * Punteggi base per difficoltà (e floor al 25%):
 *   EASY:   1000 pts  (floor 250)
 *   MEDIUM: 2000 pts  (floor 500)
 *   HARD:   3000 pts  (floor 750)
 *   EXPERT: 5000 pts  (floor 1250)
 */
object ScoringEngine {

    private const val EASY_BASE_SCORE = 1000
    private const val MEDIUM_BASE_SCORE = 2000
    private const val HARD_BASE_SCORE = 3000
    private const val EXPERT_BASE_SCORE = 5000

    // Tempo target in secondi: sotto questo si accumula swiftBonus
    private val TARGET_TIMES = mapOf(
        DifficultyLevel.EASY to 60,
        DifficultyLevel.MEDIUM to 150,
        DifficultyLevel.HARD to 300,
        DifficultyLevel.EXPERT to 600,
    )

    // Numero di mosse "ottimale" per difficoltà (percorso diretto senza deviazioni)
    private val OPTIMAL_MOVES = mapOf(
        DifficultyLevel.EASY to 20,
        DifficultyLevel.MEDIUM to 60,
        DifficultyLevel.HARD to 120,
        DifficultyLevel.EXPERT to 250,
    )

    private const val HINT_COST = 80

    /**
     * Calcola il punteggio finale per una partita completata.
     *
     * @param difficulty  Livello di difficoltà
     * @param elapsedSeconds  Secondi impiegati
     * @param hintsUsed  Numero di hint usati
     * @param moveCount  Numero totale di mosse effettuate (default 0 per compatibilità)
     * @return Punteggio finale (minimo base/4)
     */
    fun calculateScore(
        difficulty: DifficultyLevel,
        elapsedSeconds: Int,
        hintsUsed: Int,
        moveCount: Int = 0,
    ): Int {
        val base = getBaseScore(difficulty)
        val targetTime = TARGET_TIMES[difficulty] ?: 120
        val optimalMoves = OPTIMAL_MOVES[difficulty] ?: 50

        // Bonus velocità: lineare da 0 (al target time) a +60% base (a 0 secondi)
        val swiftBonus = if (elapsedSeconds < targetTime) {
            (base * 0.6f * (1f - elapsedSeconds.toFloat() / targetTime)).toInt()
        } else {
            0
        }

        // Bonus esploratore: si azzera se le mosse superano 1.5× il percorso ottimale
        val explorerBonus = if (moveCount in 1 until (optimalMoves * 1.5f).toInt()) {
            val excess = (moveCount - optimalMoves).coerceAtLeast(0)
            val maxExcess = (optimalMoves * 0.5f).coerceAtLeast(1f)
            (base * 0.2f * (1f - excess / maxExcess).coerceIn(0f, 1f)).toInt()
        } else {
            0
        }

        val rawScore = base + swiftBonus + explorerBonus - hintsUsed * HINT_COST
        // Floor al 25%: il completamento ha sempre valore, qualunque sia il percorso
        return maxOf(base / 4, rawScore)
    }

    /**
     * Overload per compatibilità con codice esistente (senza moveCount).
     */
    fun calculateScore(
        difficultyString: String,
        elapsedSeconds: Int,
        hintsUsed: Int,
    ): Int {
        val difficulty = DifficultyLevel.valueOf(difficultyString)
        return calculateScore(difficulty, elapsedSeconds, hintsUsed, moveCount = 0)
    }

    fun getBaseScore(difficulty: DifficultyLevel): Int = when (difficulty) {
        DifficultyLevel.EASY -> EASY_BASE_SCORE
        DifficultyLevel.MEDIUM -> MEDIUM_BASE_SCORE
        DifficultyLevel.HARD -> HARD_BASE_SCORE
        DifficultyLevel.EXPERT -> EXPERT_BASE_SCORE
    }

    fun getDifficultyMultiplier(difficulty: DifficultyLevel): Double = when (difficulty) {
        DifficultyLevel.EASY -> 1.0
        DifficultyLevel.MEDIUM -> 2.0
        DifficultyLevel.HARD -> 3.0
        DifficultyLevel.EXPERT -> 5.0
    }

    /** Tempo target in secondi per la difficoltà (usato per hint nell'UI). */
    fun getTargetTime(difficulty: DifficultyLevel): Int = TARGET_TIMES[difficulty] ?: 120
}
