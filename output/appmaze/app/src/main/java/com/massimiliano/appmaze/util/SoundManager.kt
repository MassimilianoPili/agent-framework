package com.massimiliano.appmaze.util

import android.content.Context
import android.media.SoundPool
import android.os.Build

/**
 * Manages sound effects using SoundPool
 */
class SoundManager(context: Context) {
    private val soundPool: SoundPool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        SoundPool.Builder()
            .setMaxStreams(5)
            .build()
    } else {
        @Suppress("DEPRECATION")
        SoundPool(5, android.media.AudioManager.STREAM_MUSIC, 0)
    }

    private val soundIds = mutableMapOf<String, Int>()

    fun loadSound(context: Context, name: String, resId: Int) {
        soundIds[name] = soundPool.load(context, resId, 1)
    }

    fun playSound(name: String) {
        soundIds[name]?.let { soundId ->
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}
