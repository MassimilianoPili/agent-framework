package com.massimiliano.appmaze.domain.maze

import kotlin.random.Random

/**
 * Difficulty levels for the maze game.
 * Each level maps to a specific grid size.
 */
enum class DifficultyLevel(val width: Int, val height: Int) {
    EASY(10, 10),
    MEDIUM(20, 20),
    HARD(30, 30),
    EXPERT(40, 40),
}

/**
 * Generates perfect mazes using the Depth-First Search (DFS) recursive backtracking algorithm.
 * A perfect maze has exactly one path between any two cells and no loops.
 */
object MazeGenerator {

    /**
     * Generates a maze with the specified dimensions.
     * @param width The width of the maze (number of columns)
     * @param height The height of the maze (number of rows)
     * @return A MazeGrid with a generated perfect maze
     */
    fun generateMaze(width: Int, height: Int): MazeGrid {
        val grid = MazeGrid(width, height)
        val startCell = grid.getCell(0, 0) ?: return grid
        
        // Perform DFS from the top-left corner
        dfs(grid, startCell)
        
        return grid
    }

    /**
     * Generates a maze with the specified difficulty level.
     * @param difficulty The difficulty level
     * @return A MazeGrid with a generated perfect maze
     */
    fun generateMaze(difficulty: DifficultyLevel): MazeGrid {
        return generateMaze(difficulty.width, difficulty.height)
    }

    /**
     * Performs depth-first search to generate the maze.
     * This is a recursive backtracking algorithm that carves passages through the maze.
     * @param grid The maze grid
     * @param cell The current cell being processed
     */
    private fun dfs(grid: MazeGrid, cell: MazeCell) {
        cell.visited = true

        // Get all unvisited neighbors
        val unvisitedNeighbors = grid.getUnvisitedNeighbors(cell).shuffled()

        for (neighbor in unvisitedNeighbors) {
            // Calculate the direction from current cell to neighbor
            val direction = getDirection(cell, neighbor)
            
            // Remove walls between current cell and neighbor
            cell.removeWall(direction)
            val oppositeDirection = grid.getOppositeDirection(direction)
            neighbor.removeWall(oppositeDirection)

            // Recursively visit the neighbor
            dfs(grid, neighbor)
        }
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
