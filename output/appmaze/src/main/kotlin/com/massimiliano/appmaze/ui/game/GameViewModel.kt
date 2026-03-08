package com.massimiliano.appmaze.ui.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.massimiliano.appmaze.data.repository.GameRepository
import com.massimiliano.appmaze.domain.game.ScoringEngine
import com.massimiliano.appmaze.domain.game.TimerManager
import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import com.massimiliano.appmaze.domain.maze.MazeCell
import com.massimiliano.appmaze.domain.maze.MazeGenerator
import com.massimiliano.appmaze.domain.maze.MazeGrid
import com.massimiliano.appmaze.domain.maze.MazeSolver
import com.massimiliano.appmaze.ui.theme.EmotionalPalette
import com.massimiliano.appmaze.util.HapticManager
import com.massimiliano.appmaze.util.SettingsManager
import com.massimiliano.appmaze.util.SoundManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// ── Event system ────────────────────────────────────────────────────────────
/**
 * Side-effect one-shot eventi di gioco, consumati via Channel.
 * Non devono essere replayati dopo un riconnect dell'observer.
 * Usare con: viewModel.events.collectLatest { event -> ... } in LaunchedEffect.
 */
sealed class GameEvent {
    data class PlayerMoved(val col: Int, val row: Int) : GameEvent()
    /** direction: 0=UP, 1=RIGHT, 2=DOWN, 3=LEFT */
    data class WallHit(val direction: Int) : GameEvent()
    object GameWon : GameEvent()
    object HintUsed : GameEvent()
    data class GlyphDiscovered(val glyph: String) : GameEvent()
    data class SecretRoomFound(val col: Int, val row: Int) : GameEvent()
    object WorldRotated : GameEvent()
    object GameStarted : GameEvent()
}

/**
 * ViewModel che gestisce lo stato e la logica del gioco con integrazione audio/haptic.
 *
 * Responsabilità:
 * - Generare il labirinto a inizio partita
 * - Tracciare la posizione del giocatore e validare le mosse
 * - Processare input swipe (SU/GIÙ/SX/DX)
 * - Gestire il timer (crescente, mettibile in pausa)
 * - Gestire le richieste di hint (solver BFS, evidenziazione percorso)
 * - Rilevare il completamento (giocatore raggiunge l'uscita)
 * - Calcolare e persistere i punteggi
 * - Emettere GameEvent per animazioni e suoni one-shot
 * - Calcolare il progresso emotivo per la palette Gris
 */
class GameViewModel(
    private val gameRepository: GameRepository,
    private val context: Context,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // Channel BUFFERED: non perde eventi se l'observer è momentaneamente sospeso
    private val _events = Channel<GameEvent>(Channel.BUFFERED)
    val events: Flow<GameEvent> = _events.receiveAsFlow()

    private var timerJob: kotlinx.coroutines.Job? = null
    private var settingsJob: kotlinx.coroutines.Job? = null

    // Cache del MazeGrid per BFS (evita ricostruzione a ogni mossa)
    private var cachedMazeGrid: MazeGrid? = null
    // Lunghezza del percorso ottimale start→exit (calcolata una sola volta per labirinto)
    private var totalPathLength: Int = -1

    init {
        viewModelScope.launch {
            SoundManager.initialize(context)
            HapticManager.initialize(context)
        }
        observeSettings()
    }

    private fun observeSettings() {
        settingsJob = viewModelScope.launch {
            settingsManager.settingsFlow.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(
                    soundEnabled = settings.soundEnabled,
                    hapticEnabled = settings.hapticEnabled,
                )
            }
        }
    }

    /**
     * Avvia una nuova partita con la difficoltà specificata.
     */
    fun startNewGame(difficulty: DifficultyLevel) {
        val maze = MazeGenerator.generateMaze(difficulty)
        val mazeGrid = (0 until maze.height).map { row ->
            (0 until maze.width).map { col ->
                maze.getCell(col, row) ?: MazeCell(col, row)
            }
        }

        // Resetta la cache BFS per il nuovo labirinto
        cachedMazeGrid = maze
        totalPathLength = -1

        _uiState.value = GameUiState(
            maze = mazeGrid,
            playerRow = 0,
            playerCol = 0,
            hintPathCells = emptyList(),
            elapsedSeconds = 0,
            score = 0,
            gameStatus = GameStatus.PLAYING,
            difficulty = difficulty,
            hintsUsed = 0,
            hintsRemaining = 3,
            soundEnabled = _uiState.value.soundEnabled,
            hapticEnabled = _uiState.value.hapticEnabled,
            visitedCells = setOf(0 to 0),
            completionProgress = 0f,
            emotionalPhase = EmotionalPalette.phaseForProgress(0f),
            moveCount = 0,
        )

        playSound(SoundManager.SoundEvent.BUTTON_CLICK)
        emitEvent(GameEvent.GameStarted)
        startTimer()
    }

    /**
     * Carica una partita salvata.
     */
    fun loadSavedGame() {
        viewModelScope.launch {
            val savedGame = gameRepository.getLatestSavedGame() ?: return@launch
            try {
                val difficulty = DifficultyLevel.valueOf(savedGame.difficulty)
                val maze = MazeGenerator.generateMaze(difficulty)
                val mazeGrid = (0 until maze.height).map { row ->
                    (0 until maze.width).map { col ->
                        maze.getCell(col, row) ?: MazeCell(col, row)
                    }
                }

                cachedMazeGrid = maze
                totalPathLength = -1

                _uiState.value = GameUiState(
                    maze = mazeGrid,
                    playerRow = savedGame.playerRow,
                    playerCol = savedGame.playerCol,
                    hintPathCells = emptyList(),
                    elapsedSeconds = savedGame.elapsedSeconds,
                    score = savedGame.currentScore,
                    gameStatus = GameStatus.PLAYING,
                    difficulty = difficulty,
                    hintsUsed = savedGame.hintsUsed,
                    hintsRemaining = 3 - savedGame.hintsUsed,
                    soundEnabled = _uiState.value.soundEnabled,
                    hapticEnabled = _uiState.value.hapticEnabled,
                    visitedCells = setOf(savedGame.playerCol to savedGame.playerRow),
                )

                startTimer(savedGame.elapsedSeconds)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Impossibile caricare la partita: ${e.message}"
                )
            }
        }
    }

    /**
     * Processa uno swipe e muove il giocatore se la mossa è valida.
     * Aggiorna progresso emotivo, trail celle visitate e moveCount.
     *
     * @param direction 0=SU, 1=DX, 2=GIÙ, 3=SX
     */
    fun processSwipe(direction: Int) {
        val currentState = _uiState.value
        if (currentState.gameStatus != GameStatus.PLAYING) return

        val (newRow, newCol) = when (direction) {
            0 -> Pair(currentState.playerRow - 1, currentState.playerCol)  // SU
            1 -> Pair(currentState.playerRow, currentState.playerCol + 1)  // DX
            2 -> Pair(currentState.playerRow + 1, currentState.playerCol)  // GIÙ
            3 -> Pair(currentState.playerRow, currentState.playerCol - 1)  // SX
            else -> return
        }

        if (isValidMove(currentState, newRow, newCol)) {
            val progress = computeProgress(newCol, newRow)
            val newVisited = currentState.visitedCells + (newCol to newRow)

            _uiState.value = currentState.copy(
                playerRow = newRow,
                playerCol = newCol,
                completionProgress = progress,
                emotionalPhase = EmotionalPalette.phaseForProgress(progress),
                visitedCells = newVisited,
                moveCount = currentState.moveCount + 1,
                hintPathCells = emptyList(),  // il trail hint sparisce dopo ogni mossa
            )

            playSound(SoundManager.SoundEvent.PLAYER_MOVE)
            triggerHaptic(HapticManager.HapticEvent.LIGHT_TAP)
            emitEvent(GameEvent.PlayerMoved(newCol, newRow))

            if (newRow == currentState.difficulty.height - 1 &&
                newCol == currentState.difficulty.width - 1
            ) {
                completeGame()
            }
        } else {
            playSound(SoundManager.SoundEvent.WALL_BUMP)
            triggerHaptic(HapticManager.HapticEvent.MEDIUM_BUZZ)
            emitEvent(GameEvent.WallHit(direction))
        }
    }

    /**
     * Calcola il progresso emotivo [0f..1f] come rapporto tra:
     *   distanza BFS da start alla posizione corrente
     *   distanza BFS da start all'uscita (percorso ottimale)
     *
     * Usa il MazeGrid cached per evitare ricostruzioni costose.
     * Il totalPathLength è calcolato una sola volta per labirinto.
     */
    private fun computeProgress(newCol: Int, newRow: Int): Float {
        val grid = cachedMazeGrid ?: return 0f
        val state = _uiState.value

        // Calcola la lunghezza del percorso ottimale (una tantum per labirinto)
        if (totalPathLength < 0) {
            val startCell = grid.getCell(0, 0) ?: return 0f
            val exitCell = grid.getCell(grid.width - 1, grid.height - 1) ?: return 0f
            totalPathLength = MazeSolver.findShortestPath(grid, startCell, exitCell).size
            if (totalPathLength <= 0) return 0f
        }

        // Percorso da start alla posizione corrente
        val startCell = grid.getCell(0, 0) ?: return state.completionProgress
        val currentCell = grid.getCell(newCol, newRow) ?: return state.completionProgress
        val pathToHere = MazeSolver.findShortestPath(grid, startCell, currentCell)

        return (pathToHere.size.toFloat() / totalPathLength).coerceIn(0f, 1f)
    }

    private fun isValidMove(state: GameUiState, newRow: Int, newCol: Int): Boolean {
        if (newRow < 0 || newRow >= state.difficulty.height ||
            newCol < 0 || newCol >= state.difficulty.width
        ) {
            return false
        }

        val currentCell = state.maze.getOrNull(state.playerRow)?.getOrNull(state.playerCol)
            ?: return false

        val direction = when {
            newRow < state.playerRow -> 0  // SU
            newCol > state.playerCol -> 1  // DX
            newRow > state.playerRow -> 2  // GIÙ
            newCol < state.playerCol -> 3  // SX
            else -> return false
        }

        return !currentCell.hasWall(direction)
    }

    /**
     * Richiede un hint: mostra le prossime 3 celle sul percorso ottimale.
     * Consuma un hint disponibile.
     */
    fun requestHint() {
        val currentState = _uiState.value
        if (currentState.hintsRemaining <= 0) return
        if (currentState.gameStatus != GameStatus.PLAYING) return

        try {
            val grid = cachedMazeGrid ?: buildAndCacheMazeGrid(currentState)

            val currentCell = grid.getCell(currentState.playerCol, currentState.playerRow) ?: return
            val exitCell = grid.getCell(
                currentState.difficulty.width - 1,
                currentState.difficulty.height - 1
            ) ?: return

            val path = MazeSolver.findShortestPath(grid, currentCell, exitCell)
            val hintCells = path.drop(1).take(3)
                .mapNotNull { c -> currentState.maze.getOrNull(c.y)?.getOrNull(c.x) }

            _uiState.value = currentState.copy(
                hintPathCells = hintCells,
                hintsUsed = currentState.hintsUsed + 1,
                hintsRemaining = currentState.hintsRemaining - 1,
            )

            playSound(SoundManager.SoundEvent.HINT_REVEAL)
            triggerHaptic(HapticManager.HapticEvent.LIGHT_TAP)
            emitEvent(GameEvent.HintUsed)
        } catch (e: Exception) {
            _uiState.value = currentState.copy(
                errorMessage = "Impossibile generare il suggerimento: ${e.message}"
            )
        }
    }

    /**
     * Costruisce e memorizza nella cache il MazeGrid a partire dalla lista corrente di celle.
     * Usato come fallback quando cachedMazeGrid non è disponibile (es. game caricato).
     */
    private fun buildAndCacheMazeGrid(state: GameUiState): MazeGrid {
        val grid = MazeGrid(state.difficulty.width, state.difficulty.height)
        for (row in state.maze.indices) {
            for (col in state.maze[row].indices) {
                val src = state.maze[row][col]
                grid.getCell(col, row)?.apply {
                    topWall = src.topWall
                    rightWall = src.rightWall
                    bottomWall = src.bottomWall
                    leftWall = src.leftWall
                }
            }
        }
        cachedMazeGrid = grid
        return grid
    }

    fun clearHint() {
        _uiState.value = _uiState.value.copy(hintPathCells = emptyList())
    }

    fun pauseGame() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(gameStatus = GameStatus.PAUSED)
        playSound(SoundManager.SoundEvent.BUTTON_CLICK)

        viewModelScope.launch {
            val state = _uiState.value
            gameRepository.saveGameState(
                difficulty = state.difficulty.name,
                mazeJson = "",
                playerRow = state.playerRow,
                playerCol = state.playerCol,
                elapsedSeconds = state.elapsedSeconds,
                currentScore = state.score,
                hintsUsed = state.hintsUsed,
            )
        }
    }

    fun resumeGame() {
        _uiState.value = _uiState.value.copy(gameStatus = GameStatus.PLAYING)
        playSound(SoundManager.SoundEvent.BUTTON_CLICK)
        startTimer(_uiState.value.elapsedSeconds)
    }

    private fun completeGame() {
        timerJob?.cancel()

        val state = _uiState.value
        val finalScore = ScoringEngine.calculateScore(
            state.difficulty,
            state.elapsedSeconds,
            state.hintsUsed,
            state.moveCount,
        )

        _uiState.value = state.copy(
            gameStatus = GameStatus.WON,
            score = finalScore,
            completionProgress = 1f,
            emotionalPhase = com.massimiliano.appmaze.ui.theme.EmotionalPhase.BLOOM,
        )

        playSound(SoundManager.SoundEvent.GAME_COMPLETE)
        triggerHaptic(HapticManager.HapticEvent.SUCCESS_PATTERN)
        emitEvent(GameEvent.GameWon)

        viewModelScope.launch {
            gameRepository.saveGameScore(
                difficulty = state.difficulty.name,
                timeSeconds = state.elapsedSeconds,
                score = finalScore,
                hintsUsed = state.hintsUsed,
            )
            gameRepository.deleteSavedGame()
        }
    }

    private fun startTimer(startFromSeconds: Int = 0) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            TimerManager.startTimer(startFromSeconds).collectLatest { elapsedSeconds ->
                _uiState.value = _uiState.value.copy(elapsedSeconds = elapsedSeconds)
            }
        }
    }

    private fun playSound(event: SoundManager.SoundEvent) {
        if (_uiState.value.soundEnabled && SoundManager.isReady()) {
            SoundManager.playSound(event)
        }
    }

    private fun triggerHaptic(event: HapticManager.HapticEvent) {
        if (_uiState.value.hapticEnabled && HapticManager.isSupported()) {
            HapticManager.trigger(event)
        }
    }

    private fun emitEvent(event: GameEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    fun toggleSound() {
        viewModelScope.launch {
            settingsManager.setSoundEnabled(!_uiState.value.soundEnabled)
        }
    }

    fun toggleHaptic() {
        viewModelScope.launch {
            settingsManager.setHapticEnabled(!_uiState.value.hapticEnabled)
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        settingsJob?.cancel()
        _events.close()
        SoundManager.release()
        HapticManager.release()
    }
}
