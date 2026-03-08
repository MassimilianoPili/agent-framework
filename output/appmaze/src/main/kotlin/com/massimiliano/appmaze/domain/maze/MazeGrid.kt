package com.massimiliano.appmaze.domain.maze

/**
 * Represents a 2D grid of maze cells.
 * Manages the grid structure and provides access to cells by coordinates.
 */
class MazeGrid(
    val width: Int,
    val height: Int,
) {
    private val cells: Array<Array<MazeCell>> = Array(height) { y ->
        Array(width) { x ->
            MazeCell(x = x, y = y)
        }
    }

    /**
     * Gets a cell at the specified coordinates.
     * @param x The x-coordinate (column)
     * @param y The y-coordinate (row)
     * @return The MazeCell at the given coordinates, or null if out of bounds
     */
    fun getCell(x: Int, y: Int): MazeCell? {
        return if (x in 0 until width && y in 0 until height) {
            cells[y][x]
        } else {
            null
        }
    }

    /**
     * Gets all cells in the grid.
     * @return A 2D array of all MazeCells
     */
    fun getAllCells(): Array<Array<MazeCell>> = cells

    /**
     * Gets the neighbors of a cell in all four directions.
     * @param cell The cell to get neighbors for
     * @return A list of neighboring cells (may be less than 4 if cell is on edge)
     */
    fun getNeighbors(cell: MazeCell): List<MazeCell> {
        val neighbors = mutableListOf<MazeCell>()
        
        // Top neighbor
        getCell(cell.x, cell.y - 1)?.let { neighbors.add(it) }
        // Right neighbor
        getCell(cell.x + 1, cell.y)?.let { neighbors.add(it) }
        // Bottom neighbor
        getCell(cell.x, cell.y + 1)?.let { neighbors.add(it) }
        // Left neighbor
        getCell(cell.x - 1, cell.y)?.let { neighbors.add(it) }
        
        return neighbors
    }

    /**
     * Gets unvisited neighbors of a cell.
     * @param cell The cell to get unvisited neighbors for
     * @return A list of unvisited neighboring cells
     */
    fun getUnvisitedNeighbors(cell: MazeCell): List<MazeCell> {
        return getNeighbors(cell).filter { !it.visited }
    }

    /**
     * Gets the neighbor in a specific direction.
     * @param cell The cell to get the neighbor from
     * @param direction The direction (0=top, 1=right, 2=bottom, 3=left)
     * @return The neighboring cell, or null if out of bounds
     */
    fun getNeighborInDirection(cell: MazeCell, direction: Int): MazeCell? {
        return when (direction) {
            0 -> getCell(cell.x, cell.y - 1)      // Top
            1 -> getCell(cell.x + 1, cell.y)      // Right
            2 -> getCell(cell.x, cell.y + 1)      // Bottom
            3 -> getCell(cell.x - 1, cell.y)      // Left
            else -> null
        }
    }

    /**
     * Gets the opposite direction.
     * @param direction The direction (0=top, 1=right, 2=bottom, 3=left)
     * @return The opposite direction
     */
    fun getOppositeDirection(direction: Int): Int {
        return when (direction) {
            0 -> 2  // Top -> Bottom
            1 -> 3  // Right -> Left
            2 -> 0  // Bottom -> Top
            3 -> 1  // Left -> Right
            else -> -1
        }
    }

    /**
     * Resets all cells in the grid to their initial state.
     */
    fun reset() {
        for (row in cells) {
            for (cell in row) {
                cell.reset()
            }
        }
    }

    /**
     * Checks if all cells in the grid have been visited.
     * @return true if all cells are visited, false otherwise
     */
    fun allCellsVisited(): Boolean {
        for (row in cells) {
            for (cell in row) {
                if (!cell.visited) return false
            }
        }
        return true
    }
}
