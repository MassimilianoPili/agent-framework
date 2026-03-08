package com.massimiliano.appmaze.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * HapticManager utility for managing haptic feedback on supported devices.
 *
 * Responsibilities:
 * - Provide haptic feedback for game events
 * - Handle API level differences (API 26+ compatible)
 * - Gracefully degrade on devices without vibrator support
 *
 * Haptic events:
 * - LIGHT_TAP: Light vibration when player moves (10ms)
 * - MEDIUM_BUZZ: Medium vibration when player hits a wall (30ms)
 * - SUCCESS_PATTERN: Complex pattern when game is completed (multi-pulse)
 *
 * API compatibility:
 * - API 26-30: Uses Vibrator directly
 * - API 31+: Uses VibratorManager for better resource management
 */
object HapticManager {
    private const val TAG = "HapticManager"

    private var vibrator: Vibrator? = null
    private var isSupported = false

    /**
     * Enum representing all haptic feedback events in the game.
     */
    enum class HapticEvent {
        LIGHT_TAP,      // Player move
        MEDIUM_BUZZ,    // Wall bump
        SUCCESS_PATTERN, // Game complete
    }

    /**
     * Initializes the HapticManager and checks for vibrator support.
     * Must be called once during app startup.
     *
     * @param context Application context
     */
    fun initialize(context: Context) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: Use VibratorManager
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                // API 26-30: Use Vibrator directly
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            isSupported = vibrator?.hasVibrator() == true
            Log.d(TAG, "HapticManager initialized. Vibrator supported: $isSupported")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HapticManager", e)
            isSupported = false
        }
    }

    /**
     * Triggers a haptic feedback event.
     * If the device doesn't support vibration, this call is silently ignored.
     *
     * @param event The haptic event to trigger
     */
    fun trigger(event: HapticEvent) {
        if (!isSupported || vibrator == null) {
            return
        }

        try {
            val effect = when (event) {
                HapticEvent.LIGHT_TAP -> createLightTap()
                HapticEvent.MEDIUM_BUZZ -> createMediumBuzz()
                HapticEvent.SUCCESS_PATTERN -> createSuccessPattern()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(10) // Fallback for older APIs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering haptic feedback: $event", e)
        }
    }

    /**
     * Creates a light tap vibration effect (10ms single pulse).
     * Used for player movement feedback.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createLightTap(): VibrationEffect {
        return VibrationEffect.createOneShot(
            10, // Duration in milliseconds
            VibrationEffect.DEFAULT_AMPLITUDE
        )
    }

    /**
     * Creates a medium buzz vibration effect (30ms single pulse).
     * Used for wall collision feedback.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediumBuzz(): VibrationEffect {
        return VibrationEffect.createOneShot(
            30, // Duration in milliseconds
            VibrationEffect.DEFAULT_AMPLITUDE
        )
    }

    /**
     * Creates a success pattern vibration effect (multi-pulse).
     * Used for game completion feedback.
     * Pattern: 100ms pause, 50ms vibrate, 50ms pause, 50ms vibrate, 100ms pause, 100ms vibrate
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSuccessPattern(): VibrationEffect {
        // Timings: [delay, vibrate, delay, vibrate, delay, vibrate]
        val timings = longArrayOf(100, 50, 50, 50, 100, 100)
        // Amplitudes: 0 = no vibration, DEFAULT_AMPLITUDE = full strength
        val amplitudes = intArrayOf(0, 100, 0, 100, 0, 255)

        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /**
     * Returns whether the device supports haptic feedback.
     */
    fun isSupported(): Boolean = isSupported

    /**
     * Cancels any ongoing haptic feedback.
     */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling haptic feedback", e)
        }
    }

    /**
     * Releases resources held by the HapticManager.
     * After calling this, initialize() should be called again if needed.
     */
    fun release() {
        try {
            vibrator?.cancel()
            vibrator = null
            isSupported = false
            Log.d(TAG, "HapticManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing HapticManager", e)
        }
    }
}
