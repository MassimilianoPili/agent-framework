package com.massimiliano.appmaze.ui.game

import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import com.massimiliano.appmaze.domain.maze.MazeCell
import com.massimiliano.appmaze.ui.theme.EmotionalPhase

/**
 * Stato completo dell'interfaccia di gioco.
 * Esposto come StateFlow da GameViewModel per aggiornamenti UI reattivi.
 *
 * I campi sono raggruppati per fase di implementazione:
 *   - Esistenti:  logica di gioco core (posizione, timer, punteggio, hint)
 *   - Fase 1:     stile emotivo (progresso, palette, trail, animazioni)
 *   - Fase 2:     prospettiva (isometrica, rotazione mondo à la Fez)
 *   - Fase 3:     narrativa (companion, glifi, stanze segrete, assist mode)
 */
data class GameUiState(

    // ── Campi esistenti ────────────────────────────────────────────────────────
    val maze: List<List<MazeCell>> = emptyList(),
    val playerRow: Int = 0,
    val playerCol: Int = 0,
    val hintPathCells: List<MazeCell> = emptyList(),
    val elapsedSeconds: Int = 0,
    val score: Int = 0,
    val gameStatus: GameStatus = GameStatus.PLAYING,
    val difficulty: DifficultyLevel = DifficultyLevel.EASY,
    val hintsUsed: Int = 0,
    val hintsRemaining: Int = 3,
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val errorMessage: String? = null,

    // ── Fase 1: Stile emotivo ──────────────────────────────────────────────────
    /** Frazione [0f..1f] del percorso ottimale percorso via BFS. Guida la palette. */
    val completionProgress: Float = 0f,
    /** Fase emotiva corrente, derivata da completionProgress. */
    val emotionalPhase: EmotionalPhase = EmotionalPhase.DORMANT,
    /** Celle visitate dal giocatore — usate per il trail visivo. */
    val visitedCells: Set<Pair<Int, Int>> = emptySet(),
    /** Dimensione pixel di una cella, calcolata dal Canvas e passata al ViewModel. */
    val cellSizePixels: Float = 0f,
    /** Offset orizzontale del canvas in pixel (per zoom/pan). */
    val mazeOffsetX: Float = 0f,
    /** Offset verticale del canvas in pixel (per zoom/pan). */
    val mazeOffsetY: Float = 0f,
    /** true mentre la win bloom animation è in corso. */
    val isWinAnimating: Boolean = false,
    /** Numero totale di mosse effettuate (include tentativi a muro). */
    val moveCount: Int = 0,

    // ── Fase 2: Prospettiva ────────────────────────────────────────────────────
    /** Modalità di visualizzazione: top-down (default) o isometrica à la Monument Valley. */
    val viewMode: ViewMode = ViewMode.TOP_DOWN,
    /** Rotazione corrente del mondo (0–3 × 90°), sistema à la Fez. */
    val worldRotation: Int = 0,
    /** Angolo visivo animato in gradi (usato per la transizione rotazione). */
    val rotationAngle: Float = 0f,
    /** Coordinate celle con passaggi nascosti attivi alla rotazione corrente. */
    val hiddenPassagesActive: Set<Pair<Int, Int>> = emptySet(),

    // ── Fase 3: Narrativa + Segreti ────────────────────────────────────────────
    /** Posizione del companion orb (null se non ancora apparso). */
    val companionPosition: Pair<Int, Int>? = null,
    /** ID dei glifi scoperti in questa sessione. */
    val discoveredGlyphs: Set<String> = emptySet(),
    /** Numero di stanze segrete trovate. */
    val secretRoomsFound: Int = 0,
    /** Assist Mode attivo (hint illimitati, timer rallentato). */
    val assistModeEnabled: Boolean = false,
    /** Contatore tentativi falliti — mostrato in modo neutro/celebrativo. */
    val deathCount: Int = 0,
    /** ID delle fragoline (collectible opzionali à la Celeste) raccolte. */
    val strawberriesCollected: Set<String> = emptySet(),
)

/** Stato del ciclo di vita della partita. */
enum class GameStatus {
    PLAYING,
    PAUSED,
    WON,
}

/** Modalità di visualizzazione del labirinto. */
enum class ViewMode {
    TOP_DOWN,
    ISOMETRIC,
}
