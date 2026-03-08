package com.massimiliano.appmaze.domain.maze

/**
 * Immutable snapshot of a generated maze.
 *
 * @param size   number of rows and columns (square grid)
 * @param cells  2-D array [row][col] of [MazeCell]
 * @param start  starting position (top-left by convention)
 * @param goal   goal position (bottom-right by convention)
 */
data class Maze(
    val size: Int,
    val cells: Array<Array<MazeCell>>,
    val start: Position = Position(0, 0),
    val goal: Position = Position(size - 1, size - 1)
) {
    /** Returns the cell at the given position, or null if out of bounds. */
    fun cellAt(pos: Position): MazeCell? =
        cells.getOrNull(pos.row)?.getOrNull(pos.col)

    /** Returns the cell at the given row/col, or null if out of bounds. */
    fun cellAt(row: Int, col: Int): MazeCell? =
        cells.getOrNull(row)?.getOrNull(col)

    // Array equality requires manual override
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Maze) return false
        return size == other.size &&
                start == other.start &&
                goal == other.goal &&
                cells.contentDeepEquals(other.cells)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + cells.contentDeepHashCode()
        result = 31 * result + start.hashCode()
        result = 31 * result + goal.hashCode()
        return result
    }
}

/**
 * A row/column coordinate in the maze grid.
 */
data class Position(val row: Int, val col: Int)
