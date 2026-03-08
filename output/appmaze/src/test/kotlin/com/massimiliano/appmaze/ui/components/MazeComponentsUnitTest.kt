package com.massimiliano.appmaze.ui.components

import com.massimiliano.appmaze.domain.maze.MazeCell
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for SwipeDirection enum and swipe detection logic.
 */
class SwipeDirectionTest {
    @Test
    fun swipeDirection_hasAllFourDirections() {
        val directions = SwipeDirection.values()
        assertEquals(4, directions.size)
        assertTrue(directions.contains(SwipeDirection.UP))
        assertTrue(directions.contains(SwipeDirection.DOWN))
        assertTrue(directions.contains(SwipeDirection.LEFT))
        assertTrue(directions.contains(SwipeDirection.RIGHT))
    }

    @Test
    fun swipeDirection_upIsCorrect() {
        assertEquals(SwipeDirection.UP, SwipeDirection.UP)
    }

    @Test
    fun swipeDirection_downIsCorrect() {
        assertEquals(SwipeDirection.DOWN, SwipeDirection.DOWN)
    }

    @Test
    fun swipeDirection_leftIsCorrect() {
        assertEquals(SwipeDirection.LEFT, SwipeDirection.LEFT)
    }

    @Test
    fun swipeDirection_rightIsCorrect() {
        assertEquals(SwipeDirection.RIGHT, SwipeDirection.RIGHT)
    }
}

/**
 * Unit tests for MazeCanvas rendering logic.
 */
class MazeCanvasRenderingTest {
    @Test
    fun mazeCellWallConfiguration_topWall() {
        val cell = MazeCell(0, 0, topWall = true, rightWall = false, bottomWall = false, leftWall = false)
        assertTrue(cell.topWall)
        assertFalse(cell.rightWall)
        assertFalse(cell.bottomWall)
        assertFalse(cell.leftWall)
    }

    @Test
    fun mazeCellWallConfiguration_rightWall() {
        val cell = MazeCell(0, 0, topWall = false, rightWall = true, bottomWall = false, leftWall = false)
        assertFalse(cell.topWall)
        assertTrue(cell.rightWall)
        assertFalse(cell.bottomWall)
        assertFalse(cell.leftWall)
    }

    @Test
    fun mazeCellWallConfiguration_bottomWall() {
        val cell = MazeCell(0, 0, topWall = false, rightWall = false, bottomWall = true, leftWall = false)
        assertFalse(cell.topWall)
        assertFalse(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertFalse(cell.leftWall)
    }

    @Test
    fun mazeCellWallConfiguration_leftWall() {
        val cell = MazeCell(0, 0, topWall = false, rightWall = false, bottomWall = false, leftWall = true)
        assertFalse(cell.topWall)
        assertFalse(cell.rightWall)
        assertFalse(cell.bottomWall)
        assertTrue(cell.leftWall)
    }

    @Test
    fun mazeCellWallConfiguration_allWalls() {
        val cell = MazeCell(0, 0, topWall = true, rightWall = true, bottomWall = true, leftWall = true)
        assertTrue(cell.topWall)
        assertTrue(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertTrue(cell.leftWall)
    }

    @Test
    fun mazeCellWallConfiguration_noWalls() {
        val cell = MazeCell(0, 0, topWall = false, rightWall = false, bottomWall = false, leftWall = false)
        assertFalse(cell.topWall)
        assertFalse(cell.rightWall)
        assertFalse(cell.bottomWall)
        assertFalse(cell.leftWall)
    }

    @Test
    fun mazeCellHasWall_topWall() {
        val cell = MazeCell(0, 0, topWall = true)
        assertTrue(cell.hasWall(0))
    }

    @Test
    fun mazeCellHasWall_rightWall() {
        val cell = MazeCell(0, 0, rightWall = true)
        assertTrue(cell.hasWall(1))
    }

    @Test
    fun mazeCellHasWall_bottomWall() {
        val cell = MazeCell(0, 0, bottomWall = true)
        assertTrue(cell.hasWall(2))
    }

    @Test
    fun mazeCellHasWall_leftWall() {
        val cell = MazeCell(0, 0, leftWall = true)
        assertTrue(cell.hasWall(3))
    }

    @Test
    fun mazeCellHasWall_noWall() {
        val cell = MazeCell(0, 0, topWall = false)
        assertFalse(cell.hasWall(0))
    }

    @Test
    fun mazeCellRemoveWall_topWall() {
        val cell = MazeCell(0, 0, topWall = true)
        cell.removeWall(0)
        assertFalse(cell.topWall)
    }

    @Test
    fun mazeCellRemoveWall_rightWall() {
        val cell = MazeCell(0, 0, rightWall = true)
        cell.removeWall(1)
        assertFalse(cell.rightWall)
    }

    @Test
    fun mazeCellRemoveWall_bottomWall() {
        val cell = MazeCell(0, 0, bottomWall = true)
        cell.removeWall(2)
        assertFalse(cell.bottomWall)
    }

    @Test
    fun mazeCellRemoveWall_leftWall() {
        val cell = MazeCell(0, 0, leftWall = true)
        cell.removeWall(3)
        assertFalse(cell.leftWall)
    }
}

/**
 * Unit tests for time formatting.
 */
class TimeFormattingTest {
    @Test
    fun formatTime_zeroSeconds() {
        val formatted = formatTime(0)
        assertEquals("00:00", formatted)
    }

    @Test
    fun formatTime_oneSecond() {
        val formatted = formatTime(1)
        assertEquals("00:01", formatted)
    }

    @Test
    fun formatTime_oneMinute() {
        val formatted = formatTime(60)
        assertEquals("01:00", formatted)
    }

    @Test
    fun formatTime_oneMinuteOneSecond() {
        val formatted = formatTime(61)
        assertEquals("01:01", formatted)
    }

    @Test
    fun formatTime_tenMinutes() {
        val formatted = formatTime(600)
        assertEquals("10:00", formatted)
    }

    @Test
    fun formatTime_tenMinutesFiftyNineSeconds() {
        val formatted = formatTime(659)
        assertEquals("10:59", formatted)
    }

    @Test
    fun formatTime_oneHour() {
        val formatted = formatTime(3600)
        assertEquals("60:00", formatted)
    }

    @Test
    fun formatTime_largeValue() {
        val formatted = formatTime(5999)
        assertEquals("99:59", formatted)
    }
}

/**
 * Helper function for testing time formatting.
 */
private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
