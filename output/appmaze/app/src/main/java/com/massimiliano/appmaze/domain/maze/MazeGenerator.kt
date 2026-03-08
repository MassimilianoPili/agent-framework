package com.massimiliano.appmaze.domain.maze

/**
 * Represents a cell in the maze grid.
 * Walls are represented as a bitmask: bit 0=North, bit 1=East, bit 2=South, bit 3=West
 */
data class MazeCell(
    val x: Int,
    val y: Int,
    var walls: Int = 0xF // All walls initially (0b1111)
) {
    fun hasWall(direction: Int): Boolean = (walls and (1 shl direction)) != 0
    fun removeWall(direction: Int) {
        walls = walls and (1 shl direction).inv()
    }
}

/**
 * Maze generator using Depth-First Search (DFS) algorithm
 */
class MazeGenerator(val width: Int, val height: Int) {
    private val grid = Array(height) { y ->
        Array(width) { x ->
            MazeCell(x, y)
        }
    }

    fun generate(): Array<Array<MazeCell>> {
        val visited = Array(height) { BooleanArray(width) }
        dfs(0, 0, visited)
        return grid
    }

    private fun dfs(x: Int, y: Int, visited: Array<BooleanArray>) {
        visited[y][x] = true

        // Directions: North, East, South, West
        val directions = listOf(
            Pair(0, -1) to 0,  // North
            Pair(1, 0) to 1,   // East
            Pair(0, 1) to 2,   // South
            Pair(-1, 0) to 3   // West
        ).shuffled()

        for ((offset, dirIndex) in directions) {
            val nx = x + offset.first
            val ny = y + offset.second

            if (nx in 0 until width && ny in 0 until height && !visited[ny][nx]) {
                // Remove walls between current and neighbor
                grid[y][x].removeWall(dirIndex)
                val oppositeDir = (dirIndex + 2) % 4
                grid[ny][nx].removeWall(oppositeDir)

                dfs(nx, ny, visited)
            }
        }
    }
}
