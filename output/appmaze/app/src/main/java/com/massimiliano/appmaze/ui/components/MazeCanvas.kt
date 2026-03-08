package com.massimiliano.appmaze.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.massimiliano.appmaze.domain.maze.Maze
import com.massimiliano.appmaze.domain.maze.Position
import com.massimiliano.appmaze.ui.theme.MazeGoalColor
import com.massimiliano.appmaze.ui.theme.MazeHintColor
import com.massimiliano.appmaze.ui.theme.MazePathColor
import com.massimiliano.appmaze.ui.theme.MazePlayerColor
import com.massimiliano.appmaze.ui.theme.MazeStartColor
import com.massimiliano.appmaze.ui.theme.MazeWallColor

/**
 * Composable that renders the maze grid using Compose [Canvas].
 *
 * Draws:
 * - Background (path colour)
 * - Walls (wall colour, 2px stroke)
 * - Start cell highlight
 * - Goal cell highlight
 * - Hint path overlay (semi-transparent yellow)
 * - Player circle
 *
 * This is a stateless composable — all state is passed in as parameters.
 *
 * @param maze        the maze to render
 * @param playerPos   current player position
 * @param hintPath    list of positions forming the BFS hint path (empty = no hint)
 * @param modifier    layout modifier
 */
@Composable
fun MazeCanvas(
    maze: Maze,
    playerPos: Position,
    hintPath: List<Position> = emptyList(),
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val cellSize = size.width / maze.size

        // Draw background
        drawRect(color = MazePathColor)

        // Draw start and goal highlights
        drawCellHighlight(maze.start, cellSize, MazeStartColor.copy(alpha = 0.4f))
        drawCellHighlight(maze.goal, cellSize, MazeGoalColor.copy(alpha = 0.4f))

        // Draw hint path
        hintPath.forEach { pos ->
            drawCellHighlight(pos, cellSize, MazeHintColor.copy(alpha = 0.35f))
        }

        // Draw walls
        for (row in 0 until maze.size) {
            for (col in 0 until maze.size) {
                val cell = maze.cells[row][col]
                val x = col * cellSize
                val y = row * cellSize
                val strokeWidth = (cellSize * 0.08f).coerceAtLeast(2f)

                if (cell.wallTop) {
                    drawLine(MazeWallColor, start = androidx.compose.ui.geometry.Offset(x, y),
                        end = androidx.compose.ui.geometry.Offset(x + cellSize, y), strokeWidth = strokeWidth)
                }
                if (cell.wallBottom) {
                    drawLine(MazeWallColor, start = androidx.compose.ui.geometry.Offset(x, y + cellSize),
                        end = androidx.compose.ui.geometry.Offset(x + cellSize, y + cellSize), strokeWidth = strokeWidth)
                }
                if (cell.wallLeft) {
                    drawLine(MazeWallColor, start = androidx.compose.ui.geometry.Offset(x, y),
                        end = androidx.compose.ui.geometry.Offset(x, y + cellSize), strokeWidth = strokeWidth)
                }
                if (cell.wallRight) {
                    drawLine(MazeWallColor, start = androidx.compose.ui.geometry.Offset(x + cellSize, y),
                        end = androidx.compose.ui.geometry.Offset(x + cellSize, y + cellSize), strokeWidth = strokeWidth)
                }
            }
        }

        // Draw player
        val playerX = playerPos.col * cellSize + cellSize / 2
        val playerY = playerPos.row * cellSize + cellSize / 2
        val playerRadius = cellSize * 0.35f
        drawCircle(
            color = MazePlayerColor,
            radius = playerRadius,
            center = androidx.compose.ui.geometry.Offset(playerX, playerY)
        )
    }
}

private fun DrawScope.drawCellHighlight(pos: Position, cellSize: Float, color: Color) {
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(pos.col * cellSize, pos.row * cellSize),
        size = androidx.compose.ui.geometry.Size(cellSize, cellSize)
    )
}
