package com.massimiliano.appmaze.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.massimiliano.appmaze.domain.maze.MazeCell
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a maze grid using Jetpack Compose Canvas.
 *
 * Features:
 * - Draws cell walls as lines
 * - Displays player position as a colored circle
 * - Marks exit cell with distinct color
 * - Overlays hint path cells with semi-transparent color
 * - Supports zoom and pan for larger mazes (30x30, 40x40)
 * - Dynamically calculates cell size based on canvas and maze dimensions
 * - Optimized for 60fps rendering (no recomposition on each frame)
 *
 * @param maze 2D array of MazeCell objects representing the maze grid
 * @param playerRow Current player row position
 * @param playerCol Current player column position
 * @param hintPathCells List of cells to highlight as hint path (empty if no hint)
 * @param modifier Modifier for the Canvas composable
 */
@Composable
fun MazeCanvas(
    maze: List<List<MazeCell>>,
    playerRow: Int,
    playerCol: Int,
    hintPathCells: List<MazeCell> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (maze.isEmpty() || maze[0].isEmpty()) {
        return
    }

    val mazeHeight = maze.size
    val mazeWidth = maze[0].size

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Colors
    val wallColor = MaterialTheme.colorScheme.onBackground
    val playerColor = MaterialTheme.colorScheme.primary
    val exitColor = MaterialTheme.colorScheme.tertiary
    val hintPathColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Calculate cell size based on canvas size and maze dimensions
            val availableWidth = size.width / scale
            val availableHeight = size.height / scale
            val cellWidth = availableWidth / mazeWidth
            val cellHeight = availableHeight / mazeHeight

            // Draw maze
            drawMaze(
                maze = maze,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                wallColor = wallColor,
                offsetX = offsetX,
                offsetY = offsetY,
                scale = scale,
            )

            // Draw hint path cells (semi-transparent overlay)
            drawHintPath(
                hintPathCells = hintPathCells,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                hintPathColor = hintPathColor,
                offsetX = offsetX,
                offsetY = offsetY,
                scale = scale,
            )

            // Draw exit cell (bottom-right corner)
            drawExitCell(
                row = mazeHeight - 1,
                col = mazeWidth - 1,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                exitColor = exitColor,
                offsetX = offsetX,
                offsetY = offsetY,
                scale = scale,
            )

            // Draw player position (colored circle)
            drawPlayerMarker(
                row = playerRow,
                col = playerCol,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                playerColor = playerColor,
                offsetX = offsetX,
                offsetY = offsetY,
                scale = scale,
            )
        }
    }
}

/**
 * Draws the maze grid with walls as lines.
 */
private fun DrawScope.drawMaze(
    maze: List<List<MazeCell>>,
    cellWidth: Float,
    cellHeight: Float,
    wallColor: Color,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
) {
    val mazeHeight = maze.size
    val mazeWidth = maze[0].size

    for (row in 0 until mazeHeight) {
        for (col in 0 until mazeWidth) {
            val cell = maze[row][col]
            val x = col * cellWidth
            val y = row * cellHeight

            // Transform coordinates for zoom/pan
            val transformedX = (x + offsetX) * scale
            val transformedY = (y + offsetY) * scale
            val transformedCellWidth = cellWidth * scale
            val transformedCellHeight = cellHeight * scale

            // Draw top wall
            if (cell.topWall) {
                drawLine(
                    color = wallColor,
                    start = Offset(transformedX, transformedY),
                    end = Offset(transformedX + transformedCellWidth, transformedY),
                    strokeWidth = 2f,
                )
            }

            // Draw right wall
            if (cell.rightWall) {
                drawLine(
                    color = wallColor,
                    start = Offset(transformedX + transformedCellWidth, transformedY),
                    end = Offset(transformedX + transformedCellWidth, transformedY + transformedCellHeight),
                    strokeWidth = 2f,
                )
            }

            // Draw bottom wall
            if (cell.bottomWall) {
                drawLine(
                    color = wallColor,
                    start = Offset(transformedX, transformedY + transformedCellHeight),
                    end = Offset(transformedX + transformedCellWidth, transformedY + transformedCellHeight),
                    strokeWidth = 2f,
                )
            }

            // Draw left wall
            if (cell.leftWall) {
                drawLine(
                    color = wallColor,
                    start = Offset(transformedX, transformedY),
                    end = Offset(transformedX, transformedY + transformedCellHeight),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

/**
 * Draws hint path cells with semi-transparent overlay.
 */
private fun DrawScope.drawHintPath(
    hintPathCells: List<MazeCell>,
    cellWidth: Float,
    cellHeight: Float,
    hintPathColor: Color,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
) {
    for (cell in hintPathCells) {
        val x = cell.x * cellWidth
        val y = cell.y * cellHeight

        val transformedX = (x + offsetX) * scale
        val transformedY = (y + offsetY) * scale
        val transformedCellWidth = cellWidth * scale
        val transformedCellHeight = cellHeight * scale

        drawRect(
            color = hintPathColor,
            topLeft = Offset(transformedX, transformedY),
            size = androidx.compose.ui.geometry.Size(transformedCellWidth, transformedCellHeight),
        )
    }
}

/**
 * Draws the exit cell with a distinct color.
 */
private fun DrawScope.drawExitCell(
    row: Int,
    col: Int,
    cellWidth: Float,
    cellHeight: Float,
    exitColor: Color,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
) {
    val x = col * cellWidth
    val y = row * cellHeight

    val transformedX = (x + offsetX) * scale
    val transformedY = (y + offsetY) * scale
    val transformedCellWidth = cellWidth * scale
    val transformedCellHeight = cellHeight * scale

    drawRect(
        color = exitColor.copy(alpha = 0.3f),
        topLeft = Offset(transformedX, transformedY),
        size = androidx.compose.ui.geometry.Size(transformedCellWidth, transformedCellHeight),
    )
}

/**
 * Draws the player marker as a colored circle in the center of the player's cell.
 */
private fun DrawScope.drawPlayerMarker(
    row: Int,
    col: Int,
    cellWidth: Float,
    cellHeight: Float,
    playerColor: Color,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
) {
    val x = col * cellWidth + cellWidth / 2
    val y = row * cellHeight + cellHeight / 2

    val transformedX = (x + offsetX) * scale
    val transformedY = (y + offsetY) * scale
    val radius = (min(cellWidth, cellHeight) / 2.5f) * scale

    drawCircle(
        color = playerColor,
        center = Offset(transformedX, transformedY),
        radius = radius,
    )
}
