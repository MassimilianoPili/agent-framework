package com.massimiliano.appmaze.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Gestisce le animazioni del giocatore con game feel ispirato a Celeste:
 *   - Squash & stretch orizzontale/verticale durante il movimento
 *   - Wall bounce con spring oscillante al colpo di muro
 *   - Spawn animation all'inizio del livello ("cade" nell'arena)
 *   - Win pop con esplosione di scala → dissolve
 *
 * Uso nel Composable:
 *   val animator = remember { PlayerAnimator() }
 *   LaunchedEffect(playerCol, playerRow) { animator.moveTo(col, row, ...) }
 *
 * Le coordinate (animX, animY) sono in pixel canvas; aggiungere shakeX/shakeY
 * per ottenere la posizione finale di rendering.
 */
class PlayerAnimator {

    /** Posizione X animata in pixel canvas. */
    val animX = Animatable(0f)
    /** Posizione Y animata in pixel canvas. */
    val animY = Animatable(0f)
    /** Offset X per il wall bounce (spring oscillante). */
    val shakeX = Animatable(0f)
    /** Offset Y per il wall bounce (spring oscillante). */
    val shakeY = Animatable(0f)
    /**
     * Scala del giocatore:
     *   - 0.82 durante movimento orizzontale (stretch laterale)
     *   - 1.15 durante movimento verticale (stretch verticale)
     *   - 1.0 a riposo
     *   - 0.0 al termine del win pop
     */
    val scale = Animatable(1f)

    /**
     * Anima il giocatore verso la cella (col, row).
     * Durata 120ms con FastOutSlowInEasing (identico al timing di Celeste).
     * Lo squash/stretch anticipa il movimento di 40ms.
     */
    suspend fun moveTo(col: Int, row: Int, cellSize: Float, offsetX: Float, offsetY: Float) {
        val tx = offsetX + col * cellSize + cellSize / 2f
        val ty = offsetY + row * cellSize + cellSize / 2f
        val isHorizontal = kotlin.math.abs(tx - animX.value) > 1f

        // Squash anticipatorio (40ms) + movimento (120ms) in parallelo
        coroutineScope {
            launch { scale.animateTo(if (isHorizontal) 0.82f else 1.15f, tween(40)) }
            launch { animX.animateTo(tx, tween(120, easing = FastOutSlowInEasing)) }
            launch { animY.animateTo(ty, tween(120, easing = FastOutSlowInEasing)) }
        }

        // Rimbalzo elastico al termine del movimento
        scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
    }

    /**
     * Rimbalzo visivo al colpo di muro.
     * Spring con oscillazione: il giocatore "sente" il muro e rimbalza indietro.
     *
     * @param direction 0=SU, 1=DX, 2=GIÙ, 3=SX
     */
    suspend fun wallBounce(direction: Int) {
        val bump = 8f
        val dx = when (direction) { 3 -> -bump; 1 -> bump; else -> 0f }
        val dy = when (direction) { 0 -> -bump; 2 -> bump; else -> 0f }

        coroutineScope {
            launch {
                shakeX.animateTo(dx, tween(50))
                shakeX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
            }
            launch {
                shakeY.animateTo(dy, tween(50))
                shakeY.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
            }
            launch {
                scale.animateTo(1.2f, tween(50))
                scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
            }
        }
    }

    /**
     * Animazione di spawn: il giocatore "cade" nell'arena dall'alto.
     * Parte 3 celle sopra la posizione iniziale con scala quasi zero.
     */
    suspend fun spawnAt(col: Int, row: Int, cellSize: Float, offsetX: Float, offsetY: Float) {
        val tx = offsetX + col * cellSize + cellSize / 2f
        val ty = offsetY + row * cellSize + cellSize / 2f

        // Posizione iniziale: 3 celle sopra, scala microscopica
        animX.snapTo(tx)
        animY.snapTo(ty - cellSize * 3f)
        scale.snapTo(0.1f)

        // Caduta con rimbalzo naturale (DampingRatioLowBouncy = molto elastico)
        coroutineScope {
            launch { animY.animateTo(ty, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) }
            launch { scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
        }
    }

    /**
     * Esplosione di vittoria: il giocatore si espande e poi sparisce.
     * L'uscita avviene con un dissolve in uscita (scale → 0),
     * segnalando visivamente l'inizio del win bloom.
     */
    suspend fun winPop() {
        scale.animateTo(1.8f, tween(200, easing = FastOutSlowInEasing))
        scale.animateTo(0f, tween(300))
    }
}
