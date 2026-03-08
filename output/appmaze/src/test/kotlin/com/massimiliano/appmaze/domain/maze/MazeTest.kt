package com.massimiliano.appmaze.domain.maze

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@DisplayName("MazeCell Tests")
class MazeCellTest {

    private lateinit var cell: MazeCell

    @BeforeEach
    fun setUp() {
        cell = MazeCell(x = 5, y = 5)
    }

    @Test
    @DisplayName("MazeCell should initialize with all walls present")
    fun testInitialState() {
        assertTrue(cell.topWall)
        assertTrue(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertTrue(cell.leftWall)
        assertFalse(cell.visited)
    }

    @Test
    @DisplayName("removeWall should remove the specified wall")
    fun testRemoveWall() {
        cell.removeWall(0)  // Remove top wall
        assertFalse(cell.topWall)
        assertTrue(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertTrue(cell.leftWall)

        cell.removeWall(1)  // Remove right wall
        assertFalse(cell.topWall)
        assertFalse(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertTrue(cell.leftWall)
    }

    @Test
    @DisplayName("hasWall should return correct wall state")
    fun testHasWall() {
        assertTrue(cell.hasWall(0))
        assertTrue(cell.hasWall(1))
        assertTrue(cell.hasWall(2))
        assertTrue(cell.hasWall(3))

        cell.removeWall(2)  // Remove bottom wall
        assertTrue(cell.hasWall(0))
        assertTrue(cell.hasWall(1))
        assertFalse(cell.hasWall(2))
        assertTrue(cell.hasWall(3))
    }

    @Test
    @DisplayName("reset should restore cell to initial state")
    fun testReset() {
        cell.visited = true
        cell.removeWall(0)
        cell.removeWall(1)

        cell.reset()

        assertTrue(cell.topWall)
        assertTrue(cell.rightWall)
        assertTrue(cell.bottomWall)
        assertTrue(cell.leftWall)
        assertFalse(cell.visited)
    }
}

@DisplayName("MazeGrid Tests")
class MazeGridTest {

    private lateinit var grid: MazeGrid

    @BeforeEach
    fun setUp() {
        grid = MazeGrid(width = 10, height = 10)
    }

    @Test
    @DisplayName("MazeGrid should initialize with correct dimensions")
    fun testGridDimensions() {
        assertEquals(10, grid.width)
        assertEquals(10, grid.height)
    }

    @Test
    @DisplayName("getCell should return correct cell at coordinates")
    fun testGetCell() {
        val cell = grid.getCell(5, 5)
        assertNotNull(cell)
        assertEquals(5, cell.x)
        assertEquals(5, cell.y)
    }

    @Test
    @DisplayName("getCell should return null for out-of-bounds coordinates")
    fun testGetCellOutOfBounds() {
        val cell1 = grid.getCell(-1, 5)
        val cell2 = grid.getCell(5, -1)
        val cell3 = grid.getCell(10, 5)
        val cell4 = grid.getCell(5, 10)

        assertEquals(null, cell1)
        assertEquals(null, cell2)
        assertEquals(null, cell3)
        assertEquals(null, cell4)
    }

    @Test
    @DisplayName("getNeighbors should return all valid neighbors")
    fun testGetNeighbors() {
        val cell = grid.getCell(5, 5)
        assertNotNull(cell)
        val neighbors = grid.getNeighbors(cell)
        assertEquals(4, neighbors.size)
    }

    @Test
    @DisplayName("getNeighbors should return fewer neighbors for edge cells")
    fun testGetNeighborsEdge() {
        val cornerCell = grid.getCell(0, 0)
        assertNotNull(cornerCell)
        val neighbors = grid.getNeighbors(cornerCell)
        assertEquals(2, neighbors.size)

        val edgeCell = grid.getCell(5, 0)
        assertNotNull(edgeCell)
        val edgeNeighbors = grid.getNeighbors(edgeCell)
        assertEquals(3, edgeNeighbors.size)
    }

    @Test
    @DisplayName("getUnvisitedNeighbors should only return unvisited cells")
    fun testGetUnvisitedNeighbors() {
        val cell = grid.getCell(5, 5)
        assertNotNull(cell)
        
        // Mark some neighbors as visited
        grid.getCell(5, 4)?.visited = true
        grid.getCell(6, 5)?.visited = true

        val unvisited = grid.getUnvisitedNeighbors(cell)
        assertEquals(2, unvisited.size)
    }

    @Test
    @DisplayName("getNeighborInDirection should return correct neighbor")
    fun testGetNeighborInDirection() {
        val cell = grid.getCell(5, 5)
        assertNotNull(cell)

        val top = grid.getNeighborInDirection(cell, 0)
        assertNotNull(top)
        assertEquals(5, top.x)
        assertEquals(4, top.y)

        val right = grid.getNeighborInDirection(cell, 1)
        assertNotNull(right)
        assertEquals(6, right.x)
        assertEquals(5, right.y)

        val bottom = grid.getNeighborInDirection(cell, 2)
        assertNotNull(bottom)
        assertEquals(5, bottom.x)
        assertEquals(6, bottom.y)

        val left = grid.getNeighborInDirection(cell, 3)
        assertNotNull(left)
        assertEquals(4, left.x)
        assertEquals(5, left.y)
    }

    @Test
    @DisplayName("getOppositeDirection should return correct opposite")
    fun testGetOppositeDirection() {
        assertEquals(2, grid.getOppositeDirection(0))  // Top -> Bottom
        assertEquals(3, grid.getOppositeDirection(1))  // Right -> Left
        assertEquals(0, grid.getOppositeDirection(2))  // Bottom -> Top
        assertEquals(1, grid.getOppositeDirection(3))  // Left -> Right
    }

    @Test
    @DisplayName("reset should reset all cells")
    fun testReset() {
        // Mark all cells as visited and remove walls
        for (row in grid.getAllCells()) {
            for (cell in row) {
                cell.visited = true
                cell.removeWall(0)
            }
        }

        grid.reset()

        for (row in grid.getAllCells()) {
            for (cell in row) {
                assertFalse(cell.visited)
                assertTrue(cell.topWall)
                assertTrue(cell.rightWall)
                assertTrue(cell.bottomWall)
                assertTrue(cell.leftWall)
            }
        }
    }

    @Test
    @DisplayName("allCellsVisited should return false initially")
    fun testAllCellsVisitedInitial() {
        assertFalse(grid.allCellsVisited())
    }

    @Test
    @DisplayName("allCellsVisited should return true when all cells visited")
    fun testAllCellsVisitedTrue() {
        for (row in grid.getAllCells()) {
            for (cell in row) {
                cell.visited = true
            }
        }
        assertTrue(grid.allCellsVisited())
    }
}

@DisplayName("MazeGenerator Tests")
class MazeGeneratorTest {

    @Test
    @DisplayName("generateMaze should create maze with correct dimensions")
    fun testGenerateMazeDimensions() {
        val maze = MazeGenerator.generateMaze(10, 10)
        assertEquals(10, maze.width)
        assertEquals(10, maze.height)
    }

    @Test
    @DisplayName("generateMaze should mark all cells as visited")
    fun testGenerateMazeAllVisited() {
        val maze = MazeGenerator.generateMaze(10, 10)
        assertTrue(maze.allCellsVisited())
    }

    @Test
    @DisplayName("generateMaze should create connected maze")
    fun testGenerateMazeConnected() {
        val maze = MazeGenerator.generateMaze(10, 10)
        val startCell = maze.getCell(0, 0)
        assertNotNull(startCell)

        // BFS to verify all cells are reachable
        val visited = mutableSetOf<MazeCell>()
        val queue = mutableListOf<MazeCell>()
        queue.add(startCell)
        visited.add(startCell)

        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            for (direction in 0..3) {
                if (!current.hasWall(direction)) {
                    val neighbor = maze.getNeighborInDirection(current, direction)
                    if (neighbor != null && neighbor !in visited) {
                        visited.add(neighbor)
                        queue.add(neighbor)
                    }
                }
            }
        }

        // All cells should be reachable
        assertEquals(100, visited.size)
    }

    @Test
    @DisplayName("generateMaze with difficulty should create correct size")
    fun testGenerateMazeWithDifficulty() {
        val easyMaze = MazeGenerator.generateMaze(DifficultyLevel.EASY)
        assertEquals(10, easyMaze.width)
        assertEquals(10, easyMaze.height)

        val mediumMaze = MazeGenerator.generateMaze(DifficultyLevel.MEDIUM)
        assertEquals(20, mediumMaze.width)
        assertEquals(20, mediumMaze.height)

        val hardMaze = MazeGenerator.generateMaze(DifficultyLevel.HARD)
        assertEquals(30, hardMaze.width)
        assertEquals(30, hardMaze.height)

        val expertMaze = MazeGenerator.generateMaze(DifficultyLevel.EXPERT)
        assertEquals(40, expertMaze.width)
        assertEquals(40, expertMaze.height)
    }

    @Test
    @DisplayName("generateMaze should create perfect maze (no loops)")
    fun testGenerateMazePerfect() {
        val maze = MazeGenerator.generateMaze(10, 10)
        
        // Count total passages (removed walls)
        var passageCount = 0
        for (row in maze.getAllCells()) {
            for (cell in row) {
                if (!cell.topWall) passageCount++
                if (!cell.rightWall) passageCount++
            }
        }

        // For a perfect maze: passages = cells - 1
        // Each passage is counted twice (once from each side), so we count only top and right
        // Total cells = 100, so passages should be 99
        assertEquals(99, passageCount)
    }

    @Test
    @DisplayName("generateMaze should produce different mazes on multiple calls")
    fun testGenerateMazeRandomness() {
        val maze1 = MazeGenerator.generateMaze(10, 10)
        val maze2 = MazeGenerator.generateMaze(10, 10)

        // Convert mazes to strings for comparison
        val maze1String = maze1.getAllCells().joinToString { row ->
            row.joinToString { cell ->
                "${cell.topWall}${cell.rightWall}${cell.bottomWall}${cell.leftWall}"
            }
        }

        val maze2String = maze2.getAllCells().joinToString { row ->
            row.joinToString { cell ->
                "${cell.topWall}${cell.rightWall}${cell.bottomWall}${cell.leftWall}"
            }
        }

        // Mazes should be different (with very high probability)
        assertFalse(maze1String == maze2String)
    }
}

@DisplayName("MazeSolver Tests")
class MazeSolverTest {

    private lateinit var maze: MazeGrid

    @BeforeEach
    fun setUp() {
        maze = MazeGenerator.generateMaze(10, 10)
    }

    @Test
    @DisplayName("findShortestPath should return path from start to exit")
    fun testFindShortestPath() {
        val startCell = maze.getCell(0, 0)
        assertNotNull(startCell)

        val path = MazeSolver.findShortestPath(maze, startCell)
        assertTrue(path.isNotEmpty())
        assertEquals(startCell, path.first())
    }

    @Test
    @DisplayName("findShortestPath should return single cell for same start and goal")
    fun testFindShortestPathSameCell() {
        val cell = maze.getCell(5, 5)
        assertNotNull(cell)

        val path = MazeSolver.findShortestPath(maze, cell, cell)
        assertEquals(1, path.size)
        assertEquals(cell, path.first())
    }

    @Test
    @DisplayName("findShortestPath should return valid path")
    fun testFindShortestPathValid() {
        val startCell = maze.getCell(0, 0)
        assertNotNull(startCell)

        val path = MazeSolver.findShortestPath(maze, startCell)
        assertTrue(MazeSolver.isPathValid(path))
    }

    @Test
    @DisplayName("isPathValid should return true for valid path")
    fun testIsPathValidTrue() {
        val cell1 = maze.getCell(0, 0)
        val cell2 = maze.getCell(0, 1)
        assertNotNull(cell1)
        assertNotNull(cell2)

        // Create a passage between cells
        cell1.bottomWall = false
        cell2.topWall = false

        val path = listOf(cell1, cell2)
        assertTrue(MazeSolver.isPathValid(path))
    }

    @Test
    @DisplayName("isPathValid should return false for invalid path with walls")
    fun testIsPathValidFalseWalls() {
        val cell1 = maze.getCell(0, 0)
        val cell2 = maze.getCell(0, 1)
        assertNotNull(cell1)
        assertNotNull(cell2)

        // Don't remove walls - path is blocked
        val path = listOf(cell1, cell2)
        assertFalse(MazeSolver.isPathValid(path))
    }

    @Test
    @DisplayName("isPathValid should return false for non-adjacent cells")
    fun testIsPathValidFalseNonAdjacent() {
        val cell1 = maze.getCell(0, 0)
        val cell2 = maze.getCell(5, 5)
        assertNotNull(cell1)
        assertNotNull(cell2)

        val path = listOf(cell1, cell2)
        assertFalse(MazeSolver.isPathValid(path))
    }

    @Test
    @DisplayName("isPathValid should return true for empty path")
    fun testIsPathValidEmpty() {
        assertTrue(MazeSolver.isPathValid(emptyList()))
    }

    @Test
    @DisplayName("isPathValid should return true for single cell path")
    fun testIsPathValidSingleCell() {
        val cell = maze.getCell(5, 5)
        assertNotNull(cell)
        assertTrue(MazeSolver.isPathValid(listOf(cell)))
    }

    @Test
    @DisplayName("findShortestPath should find path in simple maze")
    fun testFindShortestPathSimpleMaze() {
        // Create a simple 3x3 maze with a known path
        val simpleMaze = MazeGrid(3, 3)
        
        // Create a simple path: (0,0) -> (1,0) -> (2,0) -> (2,1) -> (2,2)
        val cell00 = simpleMaze.getCell(0, 0)!!
        val cell10 = simpleMaze.getCell(1, 0)!!
        val cell20 = simpleMaze.getCell(2, 0)!!
        val cell21 = simpleMaze.getCell(2, 1)!!
        val cell22 = simpleMaze.getCell(2, 2)!!

        // Remove walls to create path
        cell00.rightWall = false
        cell10.leftWall = false
        cell10.rightWall = false
        cell20.leftWall = false
        cell20.bottomWall = false
        cell21.topWall = false
        cell21.bottomWall = false
        cell22.topWall = false

        val path = MazeSolver.findShortestPath(simpleMaze, cell00, cell22)
        assertEquals(5, path.size)
        assertEquals(cell00, path[0])
        assertEquals(cell22, path[4])
        assertTrue(MazeSolver.isPathValid(path))
    }
}
