package com.massimiliano.appmaze

import org.junit.Test
import org.junit.Assert.*

class MazeGeneratorTest {
    @Test
    fun testMazeGenerationDimensions() {
        val generator = com.massimiliano.appmaze.domain.maze.MazeGenerator(10, 10)
        val maze = generator.generate()
        assertEquals(10, maze.size)
        assertEquals(10, maze[0].size)
    }

    @Test
    fun testMazeGenerationAllCellsVisited() {
        val generator = com.massimiliano.appmaze.domain.maze.MazeGenerator(5, 5)
        val maze = generator.generate()
        // Verify all cells are part of the maze (have at least one open wall)
        for (row in maze) {
            for (cell in row) {
                assertTrue(cell.walls < 0xF) // Not all walls should be present
            }
        }
    }
}
