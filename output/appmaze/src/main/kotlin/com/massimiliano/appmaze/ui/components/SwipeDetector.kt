package com.massimiliano.appmaze.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Enum representing swipe directions.
 */
enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

/**
 * Detects swipe gestures and invokes a callback with the detected direction.
 *
 * Features:
 * - Detects swipe direction (UP/DOWN/LEFT/RIGHT)
 * - Applies minimum swipe threshold to avoid accidental moves
 * - Converts swipe to direction enum
 * - Invokes callback with detected direction
 *
 * @param onSwipe Callback invoked when a swipe is detected, receives SwipeDirection
 * @param minSwipeDistance Minimum distance in pixels to register as a swipe (default: 50)
 * @param modifier Modifier for the gesture detector
 * @param content Composable content that receives swipe input
 */
@Composable
fun SwipeDetector(
    onSwipe: (SwipeDirection) -> Unit,
    minSwipeDistance: Float = 50f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pointerModifier = modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()

            val (dx, dy) = dragAmount

            // Determine if swipe is primarily horizontal or vertical
            val isHorizontal = abs(dx) > abs(dy)

            if (isHorizontal && abs(dx) > minSwipeDistance) {
                // Horizontal swipe
                val direction = if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                onSwipe(direction)
            } else if (!isHorizontal && abs(dy) > minSwipeDistance) {
                // Vertical swipe
                val direction = if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
                onSwipe(direction)
            }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = pointerModifier
    ) {
        content()
    }
}
