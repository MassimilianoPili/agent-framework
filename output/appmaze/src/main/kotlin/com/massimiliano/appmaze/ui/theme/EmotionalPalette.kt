package com.massimiliano.appmaze.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.massimiliano.appmaze.domain.maze.DifficultyLevel

/**
 * Le quattro fasi emotive che il labirinto attraversa man mano che il giocatore avanza.
 * Ispirate all'arco narrativo di Gris: dal grigio dormiente alla fioritura cromatica.
 * Il progresso è calcolato come distanza BFS percorsa / lunghezza del percorso ottimale totale.
 */
enum class EmotionalPhase(val label: String) {
    DORMANT("Dormiente"),    // 0–20%:  monocromatico, freddo
    AWAKENING("Risveglio"),  // 20–45%: prime tracce di colore
    FLOWING("Flusso"),       // 45–70%: verde caldo, ambra
    BLOOM("Fioritura"),      // 70–100%: teal brillante, oro, bianco
}

/**
 * Set completo di colori per un determinato stato emotivo del gioco.
 * Tutti e 10 i ruoli visivi sono definiti qui, eliminando costanti sparse nel canvas.
 */
data class EmotionalColors(
    val wallColor: Color,
    val pathBackground: Color,
    val playerColor: Color,
    val playerGlow: Color,
    val exitColor: Color,
    val hintColor: Color,
    val particleColor: Color,
    val vignetteColor: Color,
    val trailColor: Color,
    val companionColor: Color,
)

/**
 * Sistema di palette emotiva.
 *
 * Ogni difficoltà ha una propria identità cromatica di partenza (stagione):
 *   EASY   → Primavera (azzurro risveglio)
 *   MEDIUM → Estate (verde flusso)
 *   HARD   → Autunno (arancio bruciato)
 *   EXPERT → Inverno (blu ghiaccio)
 *
 * Avanzando nel labirinto, la palette interpola verso BLOOM indipendentemente dalla stagione.
 * Il risultato: ogni difficoltà ha un look distinto ma tutti convergono verso la stessa
 * esplosione di luce alla vittoria.
 */
object EmotionalPalette {

    private val dormant = EmotionalColors(
        wallColor = Color(0xFF3A3D4A),
        pathBackground = Color(0xFF12141C),
        playerColor = Color(0xFF8A8FA0),
        playerGlow = Color(0x448A8FA0),
        exitColor = Color(0xFF5A5D6A),
        hintColor = Color(0x665A6070),
        particleColor = Color(0x332A2D3A),
        vignetteColor = Color(0xCC0A0C14),
        trailColor = Color(0x1A8A8FA0),
        companionColor = Color(0x88B0B8C8),
    )

    private val awakening = EmotionalColors(
        wallColor = Color(0xFF2E4A6E),
        pathBackground = Color(0xFF0E1828),
        playerColor = Color(0xFF4A90D9),
        playerGlow = Color(0x664A90D9),
        exitColor = Color(0xFF6AABF0),
        hintColor = Color(0x663A70B0),
        particleColor = Color(0x443A6090),
        vignetteColor = Color(0xCC08101E),
        trailColor = Color(0x224A90D9),
        companionColor = Color(0xAA7AB8E8),
    )

    private val flowing = EmotionalColors(
        wallColor = Color(0xFF3A7A50),
        pathBackground = Color(0xFF0C1E14),
        playerColor = Color(0xFF50C878),
        playerGlow = Color(0x6650C878),
        exitColor = Color(0xFFF0C040),
        hintColor = Color(0x6640A060),
        particleColor = Color(0x5540C870),
        vignetteColor = Color(0xCC080E0C),
        trailColor = Color(0x3350C878),
        companionColor = Color(0xAAA0E8B0),
    )

    private val bloom = EmotionalColors(
        wallColor = Color(0xFF00D084),
        pathBackground = Color(0xFF0A1A14),
        playerColor = Color(0xFF00FFB0),
        playerGlow = Color(0x8800FFB0),
        exitColor = Color(0xFFFFD700),
        hintColor = Color(0x7700C880),
        particleColor = Color(0xAA00E890),
        vignetteColor = Color(0xAA040E0A),
        trailColor = Color(0x5500FFB0),
        companionColor = Color(0xFFFFE080),
    )

    /** Colori base per difficoltà — rappresentano le quattro stagioni dell'anno. */
    fun baseForDifficulty(difficulty: DifficultyLevel): EmotionalColors = when (difficulty) {
        DifficultyLevel.EASY -> awakening
        DifficultyLevel.MEDIUM -> flowing
        DifficultyLevel.HARD -> flowing.copy(            // Autunno: calore bruciato
            wallColor = Color(0xFF7A4A1E),
            playerColor = Color(0xFFD07830),
            playerGlow = Color(0x66D07830),
            trailColor = Color(0x33D07830),
        )
        DifficultyLevel.EXPERT -> awakening.copy(        // Inverno: ghiaccio siderale
            wallColor = Color(0xFF2A4A7A),
            playerColor = Color(0xFF80C8FF),
            playerGlow = Color(0x6680C8FF),
            trailColor = Color(0x2280C8FF),
        )
    }

    /**
     * Restituisce la palette interpolata per il progresso corrente [0f..1f].
     * La difficoltà definisce il punto di partenza; tutti convergono verso bloom.
     */
    fun forProgress(progress: Float, difficulty: DifficultyLevel): EmotionalColors {
        val base = baseForDifficulty(difficulty)
        return when {
            progress < 0.20f -> base
            progress < 0.45f -> lerpColors(base, awakening, (progress - 0.20f) / 0.25f)
            progress < 0.70f -> lerpColors(awakening, flowing, (progress - 0.45f) / 0.25f)
            else -> lerpColors(flowing, bloom, ((progress - 0.70f) / 0.30f).coerceIn(0f, 1f))
        }
    }

    /** Fase emotiva corrispondente al progresso. */
    fun phaseForProgress(p: Float): EmotionalPhase = when {
        p < 0.20f -> EmotionalPhase.DORMANT
        p < 0.45f -> EmotionalPhase.AWAKENING
        p < 0.70f -> EmotionalPhase.FLOWING
        else -> EmotionalPhase.BLOOM
    }

    private fun lerpColors(from: EmotionalColors, to: EmotionalColors, t: Float) = EmotionalColors(
        wallColor = lerp(from.wallColor, to.wallColor, t),
        pathBackground = lerp(from.pathBackground, to.pathBackground, t),
        playerColor = lerp(from.playerColor, to.playerColor, t),
        playerGlow = lerp(from.playerGlow, to.playerGlow, t),
        exitColor = lerp(from.exitColor, to.exitColor, t),
        hintColor = lerp(from.hintColor, to.hintColor, t),
        particleColor = lerp(from.particleColor, to.particleColor, t),
        vignetteColor = lerp(from.vignetteColor, to.vignetteColor, t),
        trailColor = lerp(from.trailColor, to.trailColor, t),
        companionColor = lerp(from.companionColor, to.companionColor, t),
    )
}
