package com.massimiliano.appmaze.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService

/**
 * Utility object for haptic feedback.
 *
 * Abstracts the API differences between Android 8 (SDK 26) and Android 12+ (SDK 31).
 * Requires VIBRATE permission in AndroidManifest.xml.
 */
object HapticUtil {

    /** Short tick — used for player movement. */
    fun tick(context: Context) = vibrate(context, 20L, VibrationEffect.EFFECT_TICK)

    /** Medium click — used for wall collision. */
    fun wallCollision(context: Context) = vibrate(context, 40L, VibrationEffect.EFFECT_HEAVY_CLICK)

    /** Long success pulse — used when the player reaches the goal. */
    fun goalReached(context: Context) = vibrate(context, 200L, VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun vibrate(context: Context, durationMs: Long, effectId: Int) {
        val vibrator = getVibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        }
    }
}
