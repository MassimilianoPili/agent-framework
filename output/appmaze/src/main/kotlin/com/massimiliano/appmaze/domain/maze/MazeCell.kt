package com.massimiliano.appmaze.domain.maze

/**
 * Represents a single cell in a maze grid.
 * Each cell has four walls (top, right, bottom, left) and a visited state.
 * Walls are represented as booleans: true = wall exists, false = wall removed (passage).
 */
data class MazeCell(
    val x: Int,
    val y: Int,
    var topWall: Boolean = true,
    var rightWall: Boolean = true,
    var bottomWall: Boolean = true,
    var leftWall: Boolean = true,
    var visited: Boolean = false,
) {
    /**
     * Removes the wall between this cell and an adjacent cell.
     * @param direction The direction of the adjacent cell (0=top, 1=right, 2=bottom, 3=left)
     */
    fun removeWall(direction: Int) {
        when (direction) {
            0 -> topWall = false      // Top
            1 -> rightWall = false    // Right
            2 -> bottomWall = false   // Bottom
            3 -> leftWall = false     // Left
        }
    }

    /**
     * Checks if a wall exists in the given direction.
     * @param direction The direction to check (0=top, 1=right, 2=bottom, 3=left)
     * @return true if the wall exists, false otherwise
     */
    fun hasWall(direction: Int): Boolean = when (direction) {
        0 -> topWall
        1 -> rightWall
        2 -> bottomWall
        3 -> leftWall
        else -> false
    }

    /**
     * Resets the cell to its initial state (all walls present, not visited).
     */
    fun reset() {
        topWall = true
        rightWall = true
        bottomWall = true
        leftWall = true
        visited = false
    }
}
