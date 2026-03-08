package com.massimiliano.appmaze

import org.junit.Test
import org.junit.Assert.*

class MazePathfinderTest {
    @Test
    fun testPathfindingSimpleMaze() {
        val generator = com.massimiliano.appmaze.domain.maze.MazeGenerator(5, 5)
        val maze = generator.generate()
        val pathfinder = com.massimiliano.appmaze.domain.maze.MazePathfinder(maze)

        val path = pathfinder.findPath(0, 0, 4, 4)
        assertTrue(path.isNotEmpty())
        assertEquals(Pair(0, 0), path.first())
        assertEquals(Pair(4, 4), path.last())
    }

    @Test
    fun testPathfindingSameStartEnd() {
        val generator = com.massimiliano.appmaze.domain.maze.MazeGenerator(5, 5)
        val maze = generator.generate()
        val pathfinder = com.massimiliano.appmaze.domain.maze.MazePathfinder(maze)

        val path = pathfinder.findPath(0, 0, 0, 0)
        assertEquals(1, path.size)
        assertEquals(Pair(0, 0), path[0])
    }
}
