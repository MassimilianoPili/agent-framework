# PIANO.md
# AppMaze — Trasformazione Visiva e Gameplay
## Ispirato a Gris · Celeste · Fez · Monument Valley

> **Stato attuale del progetto**: Core gameplay completo (DFS maze, BFS pathfinding, swipe, canvas, scoring, audio, haptics)
> **Stack**: Kotlin 1.9.22 · Jetpack Compose · Material 3 · Room · Coroutines · Android SDK 26+

---

## Visione

AppMaze parte come un gioco funzionante ma visivamente neutro.
L'obiettivo è trasformarlo in un'esperienza emotivamente risonante, ispirata a:

| Gioco | Cosa prendiamo |
|-------|----------------|
| **Gris** | Colore = narrativa, no fail state, world che reagisce al giocatore |
| **Celeste** | Game feel (squash/stretch, coyote time, wall bounce), failure = crescita, assist mode etico |
| **Fez** | 2D/3D duality, rotazione 90° rivela percorsi nascosti, linguaggio segreto |
| **Monument Valley** | Geometrie impossibili (Escher), companion silenzioso, gesture minimali |

Il progresso è diviso in **5 Fasi** implementabili sequenzialmente.
Ogni fase è autonoma: Fase 1 non richiede Fase 2, ecc.

---

## FASE 0 — Fondamenta Architetturali

> Prima di toccare lo stile, preparare le basi condivise da tutte le fasi.

### `ui/game/GameUiState.kt` — estensione completa

```kotlin
data class GameUiState(
    // ── Esistenti ──────────────────────────────────────────────────────
    val maze: MazeGrid? = null,
    val playerCol: Int = 0,
    val playerRow: Int = 0,
    val hintPath: List<MazeCell> = emptyList(),
    val elapsedSeconds: Int = 0,
    val score: Int = 0,
    val gameStatus: GameStatus = GameStatus.PLAYING,
    val difficulty: DifficultyLevel = DifficultyLevel.EASY,
    val hintsUsed: Int = 0,
    val hintsRemaining: Int = 3,
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val errorMessage: String? = null,

    // ── Fase 1: Stile emotivo ──────────────────────────────────────────
    val completionProgress: Float = 0f,             // 0f–1f
    val emotionalPhase: EmotionalPhase = EmotionalPhase.DORMANT,
    val visitedCells: Set<Pair<Int, Int>> = emptySet(),
    val cellSizePixels: Float = 0f,
    val mazeOffsetX: Float = 0f,
    val mazeOffsetY: Float = 0f,
    val isWinAnimating: Boolean = false,
    val moveCount: Int = 0,

    // ── Fase 2: Prospettiva ────────────────────────────────────────────
    val viewMode: ViewMode = ViewMode.TOP_DOWN,
    val worldRotation: Int = 0,
    val rotationAngle: Float = 0f,
    val hiddenPassagesActive: Set<Pair<Int, Int>> = emptySet(),

    // ── Fase 3: Narrativa + Segreti ────────────────────────────────────
    val companionPosition: Pair<Int, Int>? = null,
    val discoveredGlyphs: Set<String> = emptySet(),
    val secretRoomsFound: Int = 0,
    val assistModeEnabled: Boolean = false,
    val deathCount: Int = 0,
    val strawberriesCollected: Set<String> = emptySet()
)

enum class ViewMode { TOP_DOWN, ISOMETRIC }
```

### Event system nel `GameViewModel.kt`

```kotlin
sealed class GameEvent {
    data class PlayerMoved(val col: Int, val row: Int) : GameEvent()
    data class WallHit(val direction: Direction) : GameEvent()
    object GameWon : GameEvent()
    object HintUsed : GameEvent()
    data class GlyphDiscovered(val glyph: String) : GameEvent()
    data class SecretRoomFound(val col: Int, val row: Int) : GameEvent()
    object WorldRotated : GameEvent()
}

private val _events = Channel<GameEvent>(Channel.BUFFERED)
val events: Flow<GameEvent> = _events.receiveAsFlow()
```

---

## FASE 1 — Stile Emotivo (Gris + Celeste)

### Nuovo file: `ui/theme/EmotionalPalette.kt`

```kotlin
enum class EmotionalPhase(val label: String) {
    DORMANT("Dormiente"),    // 0–20%: grigio freddo
    AWAKENING("Risveglio"),  // 20–45%: prime tracce di colore
    FLOWING("Flusso"),       // 45–70%: verde caldo, amber
    BLOOM("Fioritura")       // 70–100%: teal brillante, oro, bianco
}

data class EmotionalColors(
    val wallColor: Color, val pathBackground: Color,
    val playerColor: Color, val playerGlow: Color,
    val exitColor: Color, val hintColor: Color,
    val particleColor: Color, val vignetteColor: Color,
    val trailColor: Color, val companionColor: Color
)

object EmotionalPalette {
    private val dormant = EmotionalColors(
        wallColor=Color(0xFF3A3D4A), pathBackground=Color(0xFF12141C),
        playerColor=Color(0xFF8A8FA0), playerGlow=Color(0x448A8FA0),
        exitColor=Color(0xFF5A5D6A), hintColor=Color(0x665A6070),
        particleColor=Color(0x332A2D3A), vignetteColor=Color(0xCC0A0C14),
        trailColor=Color(0x1A8A8FA0), companionColor=Color(0x88B0B8C8)
    )
    private val awakening = EmotionalColors(
        wallColor=Color(0xFF2E4A6E), pathBackground=Color(0xFF0E1828),
        playerColor=Color(0xFF4A90D9), playerGlow=Color(0x664A90D9),
        exitColor=Color(0xFF6AABF0), hintColor=Color(0x663A70B0),
        particleColor=Color(0x443A6090), vignetteColor=Color(0xCC08101E),
        trailColor=Color(0x224A90D9), companionColor=Color(0xAA7AB8E8)
    )
    private val flowing = EmotionalColors(
        wallColor=Color(0xFF3A7A50), pathBackground=Color(0xFF0C1E14),
        playerColor=Color(0xFF50C878), playerGlow=Color(0x6650C878),
        exitColor=Color(0xFFF0C040), hintColor=Color(0x6640A060),
        particleColor=Color(0x5540C870), vignetteColor=Color(0xCC080E0C),
        trailColor=Color(0x3350C878), companionColor=Color(0xAAA0E8B0)
    )
    private val bloom = EmotionalColors(
        wallColor=Color(0xFF00D084), pathBackground=Color(0xFF0A1A14),
        playerColor=Color(0xFF00FFB0), playerGlow=Color(0x8800FFB0),
        exitColor=Color(0xFFFFD700), hintColor=Color(0x7700C880),
        particleColor=Color(0xAA00E890), vignetteColor=Color(0xAA040E0A),
        trailColor=Color(0x5500FFB0), companionColor=Color(0xFFFFE080)
    )

    // Tema base per difficoltà: ogni difficoltà ha identità cromatica propria
    // EASY=Primavera(blue), MEDIUM=Estate(verde), HARD=Autunno(arancio), EXPERT=Inverno(ghiaccio)
    fun baseForDifficulty(difficulty: DifficultyLevel): EmotionalColors = when (difficulty) {
        DifficultyLevel.EASY   -> awakening
        DifficultyLevel.MEDIUM -> flowing
        DifficultyLevel.HARD   -> flowing.copy(wallColor=Color(0xFF7A4A1E), playerColor=Color(0xFFD07830))
        DifficultyLevel.EXPERT -> awakening.copy(wallColor=Color(0xFF2A4A7A), playerColor=Color(0xFF80C8FF))
    }

    fun forProgress(progress: Float, difficulty: DifficultyLevel): EmotionalColors {
        val base = baseForDifficulty(difficulty)
        return when {
            progress < 0.20f -> base
            progress < 0.45f -> lerp(base, awakening, (progress - 0.20f) / 0.25f)
            progress < 0.70f -> lerp(awakening, flowing, (progress - 0.45f) / 0.25f)
            else             -> lerp(flowing, bloom, ((progress - 0.70f) / 0.30f).coerceIn(0f, 1f))
        }
    }

    fun phaseForProgress(p: Float): EmotionalPhase = when {
        p < 0.20f -> EmotionalPhase.DORMANT
        p < 0.45f -> EmotionalPhase.AWAKENING
        p < 0.70f -> EmotionalPhase.FLOWING
        else      -> EmotionalPhase.BLOOM
    }

    private fun lerp(from: EmotionalColors, to: EmotionalColors, t: Float) = EmotionalColors(
        lerp(from.wallColor,to.wallColor,t), lerp(from.pathBackground,to.pathBackground,t),
        lerp(from.playerColor,to.playerColor,t), lerp(from.playerGlow,to.playerGlow,t),
        lerp(from.exitColor,to.exitColor,t), lerp(from.hintColor,to.hintColor,t),
        lerp(from.particleColor,to.particleColor,t), lerp(from.vignetteColor,to.vignetteColor,t),
        lerp(from.trailColor,to.trailColor,t), lerp(from.companionColor,to.companionColor,t)
    )
}
```

### Nuovo file: `ui/animation/PlayerAnimator.kt`

```kotlin
class PlayerAnimator {
    val animX = Animatable(0f);  val animY = Animatable(0f)
    val shakeX = Animatable(0f); val shakeY = Animatable(0f)
    val scale  = Animatable(1f)  // squash & stretch (Celeste)

    // Movimento fluido: 120ms, FastOutSlowIn (Celeste standard)
    suspend fun moveTo(col: Int, row: Int, cellSize: Float, offsetX: Float, offsetY: Float) {
        val tx = offsetX + col * cellSize + cellSize / 2f
        val ty = offsetY + row * cellSize + cellSize / 2f
        val isH = kotlin.math.abs(tx - animX.value) > 1f
        coroutineScope {
            launch { scale.animateTo(if (isH) 0.82f else 1.15f, tween(40)) }
            launch { animX.animateTo(tx, tween(120, easing = FastOutSlowInEasing)) }
            launch { animY.animateTo(ty, tween(120, easing = FastOutSlowInEasing)) }
        }
        scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
    }

    // Wall bounce: spring con oscillazione (Celeste: feedback visivo sulla collisione)
    suspend fun wallBounce(direction: Direction) {
        val bump = 8f
        val (dx, dy) = when (direction) {
            Direction.UP    -> 0f to -bump
            Direction.DOWN  -> 0f to bump
            Direction.LEFT  -> -bump to 0f
            Direction.RIGHT -> bump to 0f
        }
        coroutineScope {
            launch { shakeX.animateTo(dx, tween(50)); shakeX.animateTo(0f, spring()) }
            launch { shakeY.animateTo(dy, tween(50)); shakeY.animateTo(0f, spring()) }
            launch { scale.animateTo(1.2f, tween(50)); scale.animateTo(1f, spring()) }
        }
    }

    // Spawn: il giocatore "cade" nell'arena all'inizio del livello
    suspend fun spawnAt(col: Int, row: Int, cellSize: Float, offsetX: Float, offsetY: Float) {
        val tx = offsetX + col * cellSize + cellSize / 2f
        val ty = offsetY + row * cellSize + cellSize / 2f
        animX.snapTo(tx); animY.snapTo(ty - cellSize * 3f); scale.snapTo(0.1f)
        coroutineScope {
            launch { animY.animateTo(ty, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) }
            launch { scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
        }
    }

    // Win: esplosione di scala → dissolve
    suspend fun winPop() {
        scale.animateTo(1.8f, tween(200, easing = FastOutSlowInEasing))
        scale.animateTo(0f, tween(300))
    }
}
```

### Calcolo progresso in `GameViewModel.kt`

```kotlin
// Cache path length per evitare BFS ripetuti a ogni mossa
private var totalPathLength: Int = -1

private fun computeProgress(newCol: Int, newRow: Int): Float {
    val maze = _uiState.value.maze ?: return 0f
    if (totalPathLength < 0) {
        val s = maze.getCell(0, 0) ?: return 0f
        val e = maze.getCell(maze.width - 1, maze.height - 1) ?: return 0f
        totalPathLength = mazeSolver.findShortestPath(maze, s, e).size
    }
    val start   = maze.getCell(0, 0) ?: return 0f
    val current = maze.getCell(newCol, newRow) ?: return 0f
    return (mazeSolver.findShortestPath(maze, start, current).size.toFloat() / totalPathLength)
        .coerceIn(0f, 1f)
}

// In processSwipe(), dopo ogni mossa valida:
val progress = computeProgress(newCol, newRow)
_uiState.update { it.copy(
    completionProgress = progress,
    emotionalPhase = EmotionalPalette.phaseForProgress(progress),
    visitedCells = it.visitedCells + (newCol to newRow),
    moveCount = it.moveCount + 1
)}
soundManager.setAmbientProgress(progress)
```

### `ui/components/MazeCanvas.kt` — 8 layer di rendering

```
Layer 0: Background radiale (palette.pathBackground → Black)
Layer 1: Trail celle visitate (trailColor, rettangolo tenue)
Layer 2: Celle speciali (Escher connector, glyph marker, secret room indicator)
Layer 3: Pareti organiche (StrokeCap.Round, StrokeJoin.Round, wallColor)
Layer 4: Hint path (dashes animati "marching ants" + cerchio sonar sull'ultimo hint)
Layer 5: Exit cell (3 anelli concentrici pulsanti: alpha 0.15/0.30/0.90)
Layer 6: Companion orb (Fase 3 — aura + core + coda trail)
Layer 7: Player (glow radiale 3.5x + core con squash&stretch)
Layer 8: Vignette overlay (radialGradient Transparent→vignetteColor, 0.52x)
```

### Win Bloom in `ui/components/MazeGameScreen.kt`

```kotlin
var bloomVisible by remember { mutableStateOf(false) }
val bloomRadius by animateFloatAsState(
    targetValue = if (bloomVisible) 1f else 0f,
    animationSpec = tween(700, easing = FastOutSlowInEasing),
    finishedListener = { if (bloomVisible) onGameOver(score, time, difficulty) }
)
LaunchedEffect(uiState.gameStatus) {
    if (uiState.gameStatus == GameStatus.WON) {
        playerAnimator.winPop(); delay(200); bloomVisible = true
    }
}
// Overlay sopra il canvas: radialGradient playerColor+exitColor+White con bloomRadius
```

### Reframe Scoring in `domain/game/ScoringEngine.kt`

```kotlin
// PRIMA: base - timePenalty (semantica punitiva)
// DOPO:  base + swiftBonus + explorerBonus (semantica celebrativa)

fun calculateScore(difficulty: DifficultyLevel, elapsedSeconds: Int,
                   hintsUsed: Int, moveCount: Int): Int {
    val base = getBaseScore(difficulty)
    val targetTime = mapOf(EASY->60, MEDIUM->150, HARD->300, EXPERT->600)[difficulty]!!
    val swiftBonus = if (elapsedSeconds < targetTime)
        (base * 0.6f * (1f - elapsedSeconds.toFloat()/targetTime)).toInt() else 0
    val optimalMoves = mapOf(EASY->20, MEDIUM->60, HARD->120, EXPERT->250)[difficulty]!!
    val explorerBonus = if (moveCount < optimalMoves * 1.5f)
        (base * 0.2f * (1f - (moveCount-optimalMoves).toFloat()/(optimalMoves*0.5f))
            .coerceIn(0f,1f)).toInt() else 0
    return maxOf(base / 4, base + swiftBonus + explorerBonus - hintsUsed * 80)
    // Floor al 25%: il giocatore non viene mai "punito" eccessivamente
}
```

### Assist Mode — `ui/screens/AssistModeScreen.kt`

```kotlin
// Celeste: "non togliere la soddisfazione, rendere il gioco accessibile"
// Toggle: Hint illimitati | Timer slow motion (50%) | Mostra percorso completo
// NO terminologia peggiorativa ("facile", "imbroglio")
data class AssistSettings(
    val infiniteHints: Boolean = false,
    val timerSpeedMultiplier: Float = 1.0f,
    val alwaysShowPath: Boolean = false
)
```

---

## FASE 2 — Elementi Prospettici (Fez + Monument Valley)

### Nuovo file: `ui/rendering/IsometricTransform.kt`

```kotlin
// Proiezione ortografica isometrica a 30° (come Monument Valley)
object IsometricTransform {
    fun cellCenter(col: Int, row: Int, cellW: Float, ox: Float, oy: Float): Offset =
        Offset(ox + (col - row) * cellW / 2f, oy + (col + row) * cellW / 4f)

    // 4 vertici del rombo: top, right, bottom, left
    fun diamond(col: Int, row: Int, cellW: Float, ox: Float, oy: Float): Array<Offset> {
        val cx = ox + (col-row)*cellW/2f; val cy = oy + (col+row)*cellW/4f
        val hw = cellW/2f; val hh = cellW/4f
        return arrayOf(Offset(cx,cy), Offset(cx+hw,cy+hh), Offset(cx,cy+hh*2), Offset(cx-hw,cy+hh))
    }

    // Faccia sinistra blocco 3D (muro ovest)
    fun leftFace(col: Int, row: Int, cellW: Float, h: Float, ox: Float, oy: Float): Array<Offset> {
        val d = diamond(col, row, cellW, ox, oy)
        return arrayOf(d[3], d[2], d[2].copy(y=d[2].y+h), d[3].copy(y=d[3].y+h))
    }

    // Faccia destra blocco 3D (muro sud)
    fun rightFace(col: Int, row: Int, cellW: Float, h: Float, ox: Float, oy: Float): Array<Offset> {
        val d = diamond(col, row, cellW, ox, oy)
        return arrayOf(d[1], d[2], d[2].copy(y=d[2].y+h), d[1].copy(y=d[1].y+h))
    }

    // Painter's algorithm: dal basso-destra verso alto-sinistra
    fun drawOrder(cols: Int, rows: Int): List<Pair<Int,Int>> = buildList {
        for (sum in (cols+rows-2) downTo 0)
            for (c in 0 until cols) { val r = sum-c; if (r in 0 until rows) add(c to r) }
    }
}
```

### Nuovo file: `ui/rendering/PerspectiveRotation.kt`

```kotlin
// Rotazione del mondo à la Fez: 4 viste ortografiche, pareti diverse per ogni vista
class PerspectiveRotation {
    var currentRotation: Int = 0; private set
    val visualAngle = Animatable(0f)

    // 400ms, easing quintico (come Fez)
    suspend fun rotateCW() {
        visualAngle.animateTo(visualAngle.value + 90f,
            tween(400, easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)))
        currentRotation = (currentRotation + 1) % 4
    }
    suspend fun rotateCCW() {
        visualAngle.animateTo(visualAngle.value - 90f,
            tween(400, easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)))
        currentRotation = (currentRotation + 3) % 4
    }

    // Pareti visibili cambiano con la rotazione: rot0=originale, rot1=+90°, ecc.
    // Questo crea corridoi che esistono solo da certi angoli — core di Fez
    fun hasWall(cell: MazeCell, direction: Int): Boolean =
        cell.hasWall((direction + currentRotation) % 4)

    fun getPassableNeighbors(grid: MazeGrid, col: Int, row: Int): List<Pair<Int,Int>> {
        val cell = grid.getCell(col, row) ?: return emptyList()
        return buildList {
            if (!hasWall(cell, 0) && row > 0)               add(col to row - 1)
            if (!hasWall(cell, 1) && col < grid.width - 1)  add(col + 1 to row)
            if (!hasWall(cell, 2) && row < grid.height - 1) add(col to row + 1)
            if (!hasWall(cell, 3) && col > 0)               add(col - 1 to row)
        }
    }
}
```

### Modifiche a `domain/maze/MazeCell.kt`

```kotlin
enum class CellType {
    NORMAL, ESCHER_CONNECTOR, GRAVITY_FLIP,
    GLYPH_CELL, SECRET_CHAMBER_ENTRY
}
data class EscherLink(val targetCol: Int, val targetRow: Int, val visibleAtRotation: Int = 0)

// Aggiungere a MazeCell:
val cellType: CellType = CellType.NORMAL
val escherLink: EscherLink? = null
val glyphId: String? = null
```

### Modifiche a `domain/maze/MazeSolver.kt` — BFS con Escher

```kotlin
fun reachableNeighbors(grid: MazeGrid, cell: MazeCell,
                       rotation: PerspectiveRotation? = null): List<MazeCell> {
    val standard = buildList {
        val (c, r) = cell.x to cell.y
        if (rotation?.hasWall(cell,0) == false || (!cell.topWall && rotation==null))
            if (r > 0) add(grid.getCell(c, r-1))
        // ... analogamente per le 3 direzioni rimanenti
    }.filterNotNull()
    // Arco Escher (solo alla rotazione corretta)
    val escher = cell.escherLink
        ?.takeIf { rotation == null || it.visibleAtRotation == rotation.currentRotation }
        ?.let { grid.getCell(it.targetCol, it.targetRow) }
    return if (escher != null) standard + escher else standard
}
```

---

## FASE 3 — Narrativa, Companion, Segreti

### Nuovo file: `ui/animation/CompanionAnimator.kt`

```kotlin
// Orb compagno silenzioso (come Totem di Monument Valley)
// Il "lag emotivo" (spring StiffnessLow) crea attaccamento senza codice esplicativo
class CompanionAnimator {
    val animX = Animatable(0f); val animY = Animatable(0f)
    val scale = Animatable(0.8f)

    suspend fun followPlayer(targetX: Float, targetY: Float) {
        coroutineScope {
            launch { animX.animateTo(targetX, spring(0.6f, Spring.StiffnessLow)) }
            launch { animY.animateTo(targetY - 25f, spring(0.6f, Spring.StiffnessLow)) }
        }
    }

    // Si ingrandisce avvicinandosi all'exit (entusiasmo crescente, senza testo)
    suspend fun reactToProximity(distanceToExit: Float, maxDistance: Float) {
        val excitement = 1f - (distanceToExit / maxDistance).coerceIn(0f, 1f)
        scale.animateTo(0.8f + excitement * 0.4f, tween(300))
    }

    // Celebrazione win: orbita rapida (3 giri) poi scale → 0
    suspend fun celebrateWin() { /* orbita + dissolve */ }
}
```

### Nuovo file: `domain/glyph/GlyphSystem.kt`

```kotlin
// 12 glifi nascosti nei labirinti HARD/EXPERT (come il linguaggio Zu di Fez)
// Raccoglierli tutti decodifica un messaggio emotivo specifico per la difficoltà
object GlyphSystem {
    val GLYPHS = listOf("◇","△","○","□","⬡","✦","⊕","⊗","◈","⬟","◉","⬢")
    val MESSAGES = mapOf(
        "EASY"   to "Ogni passo è un passo",
        "MEDIUM" to "Perdersi è trovare",
        "HARD"   to "Il muro è un'opinione",
        "EXPERT" to "Il labirinto ti cambia mentre lo percorri"
    )

    fun requiredCount(difficulty: DifficultyLevel): Int = when (difficulty) {
        DifficultyLevel.EASY -> 3; DifficultyLevel.MEDIUM -> 6
        DifficultyLevel.HARD -> 9; DifficultyLevel.EXPERT -> 12
    }

    fun isComplete(discovered: Set<String>, difficulty: DifficultyLevel): Boolean =
        discovered.size >= requiredCount(difficulty)

    fun decodeMessage(discovered: Set<String>, difficulty: DifficultyLevel): String? =
        if (isComplete(discovered, difficulty)) MESSAGES[difficulty.name] else null
}
```

### Nuovo file: `domain/collectibles/Strawberry.kt`

```kotlin
// Come le fragoline di Celeste: opzionali, fuori dal percorso ottimale
// NON bloccano la progressione. La soddisfazione è tutta nella raccolta.
data class Strawberry(
    val id: String, val col: Int, val row: Int,
    val requiresDetour: Boolean = true
)
```

### Nuovo file: `ui/screens/ChapterTransitionScreen.kt`

```kotlin
// Transizione poetica tra difficoltà (come Gris: nessun testo esplicativo, solo emozione)
// Dissolve da palette corrente a quella del prossimo capitolo
// Frase poetica per ogni salto di difficoltà (vedi GlyphSystem.MESSAGES)
// Durata: 2500ms, poi chiama onComplete()
```

---

## FASE 4 — Sound Design

### Struttura `res/raw/`

```
Ambient (MediaPlayer, loop continui)
├── ambient_layer_1.ogg  Piano sparse, BPM 60, 120s loop     → sempre attivo (vol 0.55)
├── ambient_layer_2.ogg  Archi sottili, re minore             → attivo dal 25% (fade 2s)
├── ambient_layer_3.ogg  Synth pad caldo, do maggiore         → attivo dal 50% (fade 2s)
├── ambient_layer_4.ogg  Percussioni leggere                  → attivo dal 75% (fade 2s)
└── win_sting.ogg        Accordo maggiore, 3-4s               → al completamento

SFX (SoundPool, latenza <20ms)
├── move_soft.ogg           Passo organico (legno, 40ms)
├── wall_thud.ogg           Colpo smorzato (non metallico, 60ms)
├── hint_chime.ogg          Carillon singolo (300ms, fade out)
├── glyph_discover.ogg      Tono cristallino ascendente (500ms)
├── strawberry_collect.ogg  Bolla + shimmer (200ms)
├── rotation_whoosh.ogg     Sweep 400ms (sincronizzato con animazione)
├── companion_hum.ogg       Loop breve 2s, sottilissimo
├── secret_room.ogg         Accordo sospeso, riverbero lungo (2s)
└── chapter_transition.ogg  Accordo dissolto, filtro passa-basso (3s)
```

### 6 regole di sound design (da Celeste + Gris)

1. **La musica non si ferma mai** al reset — continuità emotiva
2. **Silenzio come strumento** — prima di eventi importanti, -20% volume per 2s
3. **SFX organici** — nessun beep, preferire materiali fisici (legno, vetro, corda)
4. **Layer entrano in fade** — mai cut improvviso (fadeInLayer: 2s per layer)
5. **Companion ha suono proprio** — loop sottile udibile in prossimità
6. **Rotazione suona come gesto fisico** — whoosh sincronizzato ai 400ms animazione

### Pitch variante per PLAYER_MOVE (Celeste: ogni passo è leggermente diverso)

```kotlin
// 5 pitch in ciclo: l'orecchio percepisce varietà senza notare la tecnica
private val movePitches = floatArrayOf(0.95f, 1.0f, 1.05f, 0.98f, 1.02f)
private var pitchIndex = 0
fun playMove() {
    soundPool?.play(soundMap[SoundEffect.PLAYER_MOVE] ?: return,
                    0.6f, 0.6f, 1, 0, movePitches[pitchIndex++ % 5])
}
```

### `SoundEffect` enum esteso

```kotlin
enum class SoundEffect {
    PLAYER_MOVE, WALL_BUMP, HINT_REVEAL, GAME_COMPLETE, BUTTON_CLICK,
    GLYPH_DISCOVERED, STRAWBERRY_COLLECT, WORLD_ROTATED,
    SECRET_ROOM_FOUND, COMPANION_PULSE
}
```

---

## FASE 5 — Architettura Dati

### Nuove entità Room

```kotlin
@Entity("glyph_progress")
data class GlyphProgressEntity(
    @PrimaryKey val glyphId: String,
    val difficulty: String,
    val discoveredAt: Long,
    val mazeWidth: Int,
    val mazeHeight: Int
)

@Entity("strawberry_collection")
data class StrawberryEntity(
    @PrimaryKey val strawberryId: String,
    val sessionId: String,
    val difficulty: String,
    val col: Int, val row: Int,
    val collectedAt: Long
)

@Entity("secret_rooms")
data class SecretRoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val rotationAngle: Int,
    val roomCol: Int, val roomRow: Int,
    val foundAt: Long
)

@Entity("player_stats")
data class PlayerStatsEntity(
    @PrimaryKey val id: Int = 1,
    val totalMoves: Long = 0,
    val totalResets: Long = 0,
    val totalGlyphsFound: Int = 0,
    val totalStrawberries: Int = 0,
    val totalSecretRooms: Int = 0,
    val totalPlaytimeSeconds: Long = 0,
    val lastPlayedAt: Long = 0
)
```

### DAOs

```kotlin
@Dao interface GlyphProgressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markDiscovered(g: GlyphProgressEntity)

    @Query("SELECT glyphId FROM glyph_progress WHERE difficulty = :diff")
    suspend fun getDiscovered(diff: String): List<String>

    @Query("SELECT COUNT(*) FROM glyph_progress WHERE difficulty = :diff")
    fun countFlow(diff: String): Flow<Int>
}

@Dao interface PlayerStatsDao {
    @Query("SELECT * FROM player_stats WHERE id = 1")
    fun getStats(): Flow<PlayerStatsEntity?>

    @Upsert
    suspend fun update(s: PlayerStatsEntity)

    @Query("UPDATE player_stats SET totalMoves = totalMoves + :d WHERE id = 1")
    suspend fun addMoves(d: Long)

    @Query("UPDATE player_stats SET totalResets = totalResets + 1 WHERE id = 1")
    suspend fun incrementResets()
}
```

### Migration Room 1→2

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS glyph_progress (
            glyphId TEXT PRIMARY KEY NOT NULL, difficulty TEXT NOT NULL,
            discoveredAt INTEGER NOT NULL, mazeWidth INTEGER NOT NULL,
            mazeHeight INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS player_stats (
            id INTEGER PRIMARY KEY NOT NULL,
            totalMoves INTEGER NOT NULL DEFAULT 0,
            totalResets INTEGER NOT NULL DEFAULT 0,
            totalGlyphsFound INTEGER NOT NULL DEFAULT 0,
            totalStrawberries INTEGER NOT NULL DEFAULT 0,
            totalSecretRooms INTEGER NOT NULL DEFAULT 0,
            totalPlaytimeSeconds INTEGER NOT NULL DEFAULT 0,
            lastPlayedAt INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("INSERT INTO player_stats (id) VALUES (1)")
    }
}
```

### `ui/screens/StatsScreen.kt`

```kotlin
// Statistiche aggregate — stile Celeste: celebrativo, mai punitivo
// "X mosse totali · Y glifi trovati · Z fragoline · W ore di gioco"
// Se tutti i glifi trovati: mostrare il messaggio decodificato in evidenza
```

---

## File Coinvolti — Riepilogo

| # | File | Tipo | Fase |
|---|------|------|------|
| 1 | `ui/theme/EmotionalPalette.kt` | Nuovo | 0+1 |
| 2 | `ui/game/GameUiState.kt` | Modifica totale | 0 |
| 3 | `ui/game/GameViewModel.kt` | Modifica estesa | 0+1+2+3 |
| 4 | `ui/animation/PlayerAnimator.kt` | Nuovo | 1 |
| 5 | `ui/animation/CompanionAnimator.kt` | Nuovo | 3 |
| 6 | `ui/effects/AmbientParticles.kt` | Nuovo | 1 |
| 7 | `ui/components/MazeCanvas.kt` | Riscrittura | 1+2+3 |
| 8 | `domain/game/ScoringEngine.kt` | Modifica | 1 |
| 9 | `util/SoundManager.kt` | Modifica | 1+4 |
| 10 | `ui/components/MazeGameScreen.kt` | Modifica estesa | 1+2 |
| 11 | `ui/screens/HomeScreen.kt` | Modifica | 1 |
| 12 | `ui/screens/GameOverScreen.kt` | Modifica | 1+3 |
| 13 | `ui/screens/AssistModeScreen.kt` | Nuovo | 1 |
| 14 | `ui/screens/ChapterTransitionScreen.kt` | Nuovo | 3 |
| 15 | `ui/screens/StatsScreen.kt` | Nuovo | 5 |
| 16 | `ui/rendering/IsometricTransform.kt` | Nuovo | 2 |
| 17 | `ui/rendering/PerspectiveRotation.kt` | Nuovo | 2 |
| 18 | `domain/maze/MazeCell.kt` | Modifica | 2+3 |
| 19 | `domain/maze/MazeGenerator.kt` | Modifica | 2+3 |
| 20 | `domain/maze/MazeSolver.kt` | Modifica | 2 |
| 21 | `domain/maze/SecretRoom.kt` | Nuovo | 3 |
| 22 | `domain/glyph/GlyphSystem.kt` | Nuovo | 3 |
| 23 | `domain/collectibles/Strawberry.kt` | Nuovo | 3 |
| 24 | `data/entity/GlyphProgressEntity.kt` | Nuovo | 5 |
| 25 | `data/entity/StrawberryEntity.kt` | Nuovo | 5 |
| 26 | `data/entity/SecretRoomEntity.kt` | Nuovo | 5 |
| 27 | `data/entity/PlayerStatsEntity.kt` | Nuovo | 5 |
| 28 | `data/db/GlyphProgressDao.kt` | Nuovo | 5 |
| 29 | `data/db/PlayerStatsDao.kt` | Nuovo | 5 |
| 30 | `data/db/AppMazeDatabase.kt` | Modifica | 5 |
| 31 | `data/repository/GameRepository.kt` | Modifica | 5 |

---

## Ordine di Implementazione

```
── Fase 0 ─────────────────────────────────────────────
  1. GameUiState.kt       — tutti i campi (0+1+2+3)
  2. GameViewModel.kt     — event channel + progress calc

── Fase 1 ─────────────────────────────────────────────
  3. EmotionalPalette.kt
  4. PlayerAnimator.kt
  5. AmbientParticles.kt
  6. MazeCanvas.kt        — layer 0-5 (senza companion)
  7. ScoringEngine.kt
  8. SoundManager.kt      — ambient layers + SFX estesi
  9. MazeGameScreen.kt    — win bloom
 10. HomeScreen.kt        — atmosferica + particelle
 11. GameOverScreen.kt    — death counter
 12. AssistModeScreen.kt

── Fase 2 ─────────────────────────────────────────────
 13. IsometricTransform.kt
 14. PerspectiveRotation.kt
 15. MazeCell.kt + MazeGenerator.kt (CellType, EscherLink)
 16. MazeSolver.kt        — BFS con Escher + rotazione
 17. MazeCanvas.kt        — aggiungere drawIsometric()
 18. MazeGameScreen.kt    — rotation buttons + indicatore

── Fase 3 ─────────────────────────────────────────────
 19. CompanionAnimator.kt
 20. MazeCanvas.kt        — layer companion
 21. GlyphSystem.kt
 22. SecretRoom.kt + Strawberry.kt
 23. ChapterTransitionScreen.kt

── Fase 4 ─────────────────────────────────────────────
 24. File audio res/raw/  — placeholder .ogg
 25. SoundManager.kt      — pitch variant + nuovi SFX

── Fase 5 ─────────────────────────────────────────────
 26. Entità Room          — 4 nuove entity class
 27. DAOs                 — GlyphProgressDao, PlayerStatsDao
 28. AppMazeDatabase.kt   — migration 1→2
 29. GameRepository.kt    — metodi per glifi e stats
 30. StatsScreen.kt
```

---

## Verifica

```bash
cd /data/massimiliano/agent-framework/output/appmaze
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest
```

### Checklist Fase 1
- [ ] Palette evolve da tono-base-difficoltà a bloom avanzando
- [ ] 4 difficoltà con identità cromatica distinta (primavera/estate/autunno/inverno)
- [ ] Movimento 120ms con squash & stretch (diverso per asse orizzontale/verticale)
- [ ] Wall bounce spring con oscillazione
- [ ] Spawn animation all'inizio livello
- [ ] Trail celle visitate (trailColor crescente)
- [ ] Pareti StrokeCap.Round + StrokeJoin.Round
- [ ] Glow radiale 3.5x + core player con scala animata
- [ ] Exit: 3 anelli concentrici pulsanti (alpha 0.15/0.30/0.90)
- [ ] Hint: dashes animati + cerchio sonar sull'ultimo hint
- [ ] Vignette pesante ai bordi (0.52x)
- [ ] Particelle ambient in HomeScreen
- [ ] Win bloom animation + winPop() del player → GameOverScreen
- [ ] 4 layer audio progressivi (attivati a 0/25/50/75%)
- [ ] Pitch variante su ogni passo (array 5 valori, ciclo)
- [ ] Scoring: swift bonus + explorer bonus, floor 25%
- [ ] Death counter neutro nel GameOverScreen
- [ ] Assist Mode accessibile dalle impostazioni

### Checklist Fase 2
- [ ] Toggle TOP_DOWN/ISOMETRIC nel HUD
- [ ] Vista isometrica: rombi + blocchi 3D, painter's algorithm corretto
- [ ] Rotazione 90° animata (400ms, quintic easing CubicBezier)
- [ ] Indicatore 4-punti rotazione corrente
- [ ] Pareti cambiano a rotazione diversa (percorsi nuovi scoperti)
- [ ] Escher connector: BFS naviga attraverso alla rotazione corretta

### Checklist Fase 3
- [ ] Companion appare dopo 30% di progresso
- [ ] Companion segue con lag emotivo (spring StiffnessLow)
- [ ] Companion scala in prossimità dell'exit
- [ ] Companion celebra il win (orbita 3 giri + dissolve)
- [ ] Glifi visibili nei labirinti HARD/EXPERT
- [ ] Raccoglierli tutti → messaggio decodificato nel GameOverScreen
- [ ] Fragoline raccoglibili fuori dal percorso ottimale
- [ ] Transizione poetica tra livelli di difficoltà

### Checklist Fase 5
- [ ] Migration Room da v1 a v2 senza perdita dati
- [ ] Glifi persistono tra sessioni (Room DB)
- [ ] StatsScreen mostra totali in modo celebrativo
- [ ] Se glifi completi: messaggio visibile nelle stats
