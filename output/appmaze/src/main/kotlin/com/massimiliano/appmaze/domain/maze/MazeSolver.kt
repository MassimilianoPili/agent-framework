package com.massimiliano.appmaze.domain.maze

import java.util.LinkedList
import java.util.Queue

/**
 * Solves mazes using the Breadth-First Search (BFS) algorithm.
 * BFS guarantees finding the shortest path from start to goal.
 * Used for the hint system to display the solution path.
 */
object MazeSolver {

    /**
     * Finds the shortest path from a start cell to an exit cell using BFS.
     * The exit is assumed to be at the bottom-right corner of the maze.
     * @param grid The maze grid
     * @param startCell The starting cell
     * @return A list of cells representing the shortest path from start to exit,
     *         or an empty list if no path exists
     */
    fun findShortestPath(grid: MazeGrid, startCell: MazeCell): List<MazeCell> {
        val exitCell = grid.getCell(grid.width - 1, grid.height - 1) ?: return emptyList()
        return findShortestPath(grid, startCell, exitCell)
    }

    /**
     * Finds the shortest path between two cells using BFS.
     * @param grid The maze grid
     * @param startCell The starting cell
     * @param goalCell The goal cell
     * @return A list of cells representing the shortest path from start to goal,
     *         or an empty list if no path exists
     */
    fun findShortestPath(grid: MazeGrid, startCell: MazeCell, goalCell: MazeCell): List<MazeCell> {
        if (startCell == goalCell) {
            return listOf(startCell)
        }

        val visited = mutableSetOf<MazeCell>()
        val queue: Queue<MazeCell> = LinkedList()
        val parentMap = mutableMapOf<MazeCell, MazeCell?>()

        queue.add(startCell)
        visited.add(startCell)
        parentMap[startCell] = null

        while (queue.isNotEmpty()) {
            val currentCell = queue.poll()

            if (currentCell == goalCell) {
                // Reconstruct the path
                return reconstructPath(parentMap, goalCell)
            }

            // Explore all neighbors that are reachable (no wall between them)
            val neighbors = getReachableNeighbors(grid, currentCell)
            for (neighbor in neighbors) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    parentMap[neighbor] = currentCell
                    queue.add(neighbor)
                }
            }
        }

        // No path found
        return emptyList()
    }

    /**
     * Gets all neighbors of a cell that are reachable (no wall blocking the passage).
     * @param grid The maze grid
     * @param cell The cell to get reachable neighbors for
     * @return A list of reachable neighboring cells
     */
    private fun getReachableNeighbors(grid: MazeGrid, cell: MazeCell): List<MazeCell> {
        val neighbors = mutableListOf<MazeCell>()

        // Check all four directions
        for (direction in 0..3) {
            // If there's no wall in this direction, the neighbor is reachable
            if (!cell.hasWall(direction)) {
                val neighbor = grid.getNeighborInDirection(cell, direction)
                if (neighbor != null) {
                    neighbors.add(neighbor)
                }
            }
        }

        return neighbors
    }

    /**
     * Reconstructs the path from start to goal using the parent map.
     * @param parentMap A map of each cell to its parent in the BFS tree
     * @param goalCell The goal cell
     * @return A list of cells representing the path from start to goal
     */
    private fun reconstructPath(parentMap: Map<MazeCell, MazeCell?>, goalCell: MazeCell): List<MazeCell> {
        val path = mutableListOf<MazeCell>()
        var currentCell: MazeCell? = goalCell

        while (currentCell != null) {
            path.add(0, currentCell)
            currentCell = parentMap[currentCell]
        }

        return path
    }

    /**
     * Checks if a path is valid (all consecutive cells are reachable without walls).
     * @param path The path to validate
     * @return true if the path is valid, false otherwise
     */
    fun isPathValid(path: List<MazeCell>): Boolean {
        if (path.isEmpty()) return true
        if (path.size == 1) return true

        for (i in 0 until path.size - 1) {
            val currentCell = path[i]
            val nextCell = path[i + 1]

            // Check if nextCell is a neighbor of currentCell
            val isNeighbor = (currentCell.x == nextCell.x && Math.abs(currentCell.y - nextCell.y) == 1) ||
                    (currentCell.y == nextCell.y && Math.abs(currentCell.x - nextCell.x) == 1)

            if (!isNeighbor) return false

            // Check if there's a wall between them
            val direction = getDirection(currentCell, nextCell)
            if (currentCell.hasWall(direction)) return false
        }

        return true
    }

    /**
     * Determines the direction from one cell to another.
     * @param from The source cell
     * @param to The target cell
     * @return The direction (0=top, 1=right, 2=bottom, 3=left)
     */
    private fun getDirection(from: MazeCell, to: MazeCell): Int {
        return when {
            to.y < from.y -> 0  // Top
            to.x > from.x -> 1  // Right
            to.y > from.y -> 2  // Bottom
            to.x < from.x -> 3  // Left
            else -> -1
        }
    }
}
