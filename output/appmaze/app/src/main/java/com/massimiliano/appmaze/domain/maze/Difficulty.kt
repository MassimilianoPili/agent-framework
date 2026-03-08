package com.massimiliano.appmaze.domain.maze

/**
 * Difficulty levels for the maze game.
 *
 * Each level maps to a square grid size used by the DFS maze generator.
 *
 * | Level  | Grid    | Description                          |
 * |--------|---------|--------------------------------------|
 * | EASY   | 10×10   | Quick game, suitable for beginners   |
 * | MEDIUM | 20×20   | Moderate challenge                   |
 * | HARD   | 30×30   | Long paths, many dead ends           |
 * | EXPERT | 40×40   | Maximum complexity                   |
 */
enum class Difficulty(
    val displayName: String,
    val gridSize: Int,
    val emoji: String
) {
    EASY(displayName = "Easy", gridSize = 10, emoji = "🟢"),
    MEDIUM(displayName = "Medium", gridSize = 20, emoji = "🟡"),
    HARD(displayName = "Hard", gridSize = 30, emoji = "🟠"),
    EXPERT(displayName = "Expert", gridSize = 40, emoji = "🔴")
}
