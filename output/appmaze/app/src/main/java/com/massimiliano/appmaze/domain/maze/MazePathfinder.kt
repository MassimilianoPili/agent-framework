package com.massimiliano.appmaze.domain.maze

import java.util.LinkedList
import java.util.Queue

/**
 * Breadth-First Search (BFS) pathfinding for maze hints
 */
class MazePathfinder(private val grid: Array<Array<MazeCell>>) {
    private val width = grid[0].size
    private val height = grid.size

    fun findPath(startX: Int, startY: Int, endX: Int, endY: Int): List<Pair<Int, Int>> {
        val visited = Array(height) { BooleanArray(width) }
        val parent = Array(height) { Array(width) { Pair(-1, -1) } }
        val queue: Queue<Pair<Int, Int>> = LinkedList()

        queue.offer(Pair(startX, startY))
        visited[startY][startX] = true

        while (queue.isNotEmpty()) {
            val (x, y) = queue.poll()

            if (x == endX && y == endY) {
                return reconstructPath(parent, startX, startY, endX, endY)
            }

            // Check all four directions
            val directions = listOf(
                Pair(0, -1) to 0,  // North
                Pair(1, 0) to 1,   // East
                Pair(0, 1) to 2,   // South
                Pair(-1, 0) to 3   // West
            )

            for ((offset, dirIndex) in directions) {
                val nx = x + offset.first
                val ny = y + offset.second

                if (nx in 0 until width && ny in 0 until height && !visited[ny][nx]) {
                    // Check if there's no wall in this direction
                    if (!grid[y][x].hasWall(dirIndex)) {
                        visited[ny][nx] = true
                        parent[ny][nx] = Pair(x, y)
                        queue.offer(Pair(nx, ny))
                    }
                }
            }
        }

        return emptyList() // No path found
    }

    private fun reconstructPath(
        parent: Array<Array<Pair<Int, Int>>>,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int
    ): List<Pair<Int, Int>> {
        val path = mutableListOf<Pair<Int, Int>>()
        var x = endX
        var y = endY

        while (x != startX || y != startY) {
            path.add(0, Pair(x, y))
            val (px, py) = parent[y][x]
            x = px
            y = py
        }
        path.add(0, Pair(startX, startY))
        return path
    }
}
