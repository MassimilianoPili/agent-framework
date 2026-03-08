package com.massimiliano.appmaze.domain.maze

/**
 * Represents a single cell in the maze grid.
 *
 * Walls are stored as four booleans (one per cardinal direction).
 * A wall value of `true` means the wall is present (passage blocked).
 *
 * The DFS generator removes walls between adjacent cells to carve passages.
 *
 * @param row       row index (0-based, top to bottom)
 * @param col       column index (0-based, left to right)
 * @param wallTop   wall on the north side
 * @param wallBottom wall on the south side
 * @param wallLeft  wall on the west side
 * @param wallRight wall on the east side
 * @param visited   used internally by the DFS generator; not part of game state
 */
data class MazeCell(
    val row: Int,
    val col: Int,
    var wallTop: Boolean = true,
    var wallBottom: Boolean = true,
    var wallLeft: Boolean = true,
    var wallRight: Boolean = true,
    var visited: Boolean = false
)
