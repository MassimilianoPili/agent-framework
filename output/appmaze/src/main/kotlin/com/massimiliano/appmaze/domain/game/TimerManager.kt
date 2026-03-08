package com.massimiliano.appmaze.domain.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Utility for managing game timers using coroutines.
 * Provides precise second-by-second counting for elapsed time tracking.
 *
 * Usage:
 * ```
 * val timerFlow = TimerManager.startTimer()
 * timerFlow.collect { elapsedSeconds ->
 *     println("Elapsed: $elapsedSeconds seconds")
 * }
 * ```
 */
object TimerManager {

    /**
     * Starts a timer that emits elapsed seconds.
     * The timer counts up from 0 and emits a new value every second.
     *
     * @param startFromSeconds Optional starting value (default: 0)
     * @return A Flow that emits elapsed seconds
     */
    fun startTimer(startFromSeconds: Int = 0): Flow<Int> = flow {
        var elapsedSeconds = startFromSeconds
        while (true) {
            emit(elapsedSeconds)
            delay(1000)  // Wait 1 second before emitting next value
            elapsedSeconds++
        }
    }

    /**
     * Starts a countdown timer that emits remaining seconds.
     * The timer counts down from the given duration and completes when reaching 0.
     *
     * @param durationSeconds The total duration in seconds
     * @return A Flow that emits remaining seconds
     */
    fun startCountdown(durationSeconds: Int): Flow<Int> = flow {
        var remainingSeconds = durationSeconds
        while (remainingSeconds >= 0) {
            emit(remainingSeconds)
            if (remainingSeconds == 0) break
            delay(1000)  // Wait 1 second before emitting next value
            remainingSeconds--
        }
    }

    /**
     * Formats elapsed seconds into a human-readable time string (MM:SS).
     *
     * @param elapsedSeconds The elapsed time in seconds
     * @return A formatted string like "01:23" for 83 seconds
     */
    fun formatElapsedTime(elapsedSeconds: Int): String {
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * Formats remaining seconds into a human-readable time string (MM:SS).
     *
     * @param remainingSeconds The remaining time in seconds
     * @return A formatted string like "01:23" for 83 seconds
     */
    fun formatRemainingTime(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
