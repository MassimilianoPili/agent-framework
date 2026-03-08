package com.massimiliano.appmaze.domain.game

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("TimerManager Tests")
class TimerManagerTest {

    @Test
    @DisplayName("Timer starts at 0 and counts up")
    fun timerCountsUp() = runTest {
        val values = mutableListOf<Int>()
        val timerFlow = TimerManager.startTimer()

        // Collect first 5 values
        timerFlow.collect { value ->
            values.add(value)
            if (values.size >= 5) {
                throw Exception("Stop collection")
            }
        }
    }

    @Test
    @DisplayName("Timer can start from a specific value")
    fun timerStartsFromValue() = runTest {
        val values = mutableListOf<Int>()
        val timerFlow = TimerManager.startTimer(startFromSeconds = 100)

        // Collect first 3 values
        timerFlow.collect { value ->
            values.add(value)
            if (values.size >= 3) {
                throw Exception("Stop collection")
            }
        }
    }

    @Test
    @DisplayName("Countdown timer counts down from duration")
    fun countdownTimer() = runTest {
        val values = mutableListOf<Int>()
        val countdownFlow = TimerManager.startCountdown(durationSeconds = 5)

        countdownFlow.collect { value ->
            values.add(value)
        }

        // Should emit 6 values: 5, 4, 3, 2, 1, 0
        assertEquals(6, values.size)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), values)
    }

    @Test
    @DisplayName("Format elapsed time: 0 seconds")
    fun formatZeroSeconds() {
        val formatted = TimerManager.formatElapsedTime(0)
        assertEquals("00:00", formatted)
    }

    @Test
    @DisplayName("Format elapsed time: 45 seconds")
    fun formatSeconds() {
        val formatted = TimerManager.formatElapsedTime(45)
        assertEquals("00:45", formatted)
    }

    @Test
    @DisplayName("Format elapsed time: 1 minute 30 seconds")
    fun formatMinutesAndSeconds() {
        val formatted = TimerManager.formatElapsedTime(90)
        assertEquals("01:30", formatted)
    }

    @Test
    @DisplayName("Format elapsed time: 2 minutes 15 seconds")
    fun formatMultipleMinutes() {
        val formatted = TimerManager.formatElapsedTime(135)
        assertEquals("02:15", formatted)
    }

    @Test
    @DisplayName("Format elapsed time: 59 minutes 59 seconds")
    fun formatMaxTime() {
        val formatted = TimerManager.formatElapsedTime(3599)
        assertEquals("59:59", formatted)
    }

    @Test
    @DisplayName("Format remaining time: 30 seconds")
    fun formatRemainingSeconds() {
        val formatted = TimerManager.formatRemainingTime(30)
        assertEquals("00:30", formatted)
    }

    @Test
    @DisplayName("Format remaining time: 2 minutes")
    fun formatRemainingMinutes() {
        val formatted = TimerManager.formatRemainingTime(120)
        assertEquals("02:00", formatted)
    }

    @Test
    @DisplayName("Format remaining time: 1 minute 45 seconds")
    fun formatRemainingMixed() {
        val formatted = TimerManager.formatRemainingTime(105)
        assertEquals("01:45", formatted)
    }
}
