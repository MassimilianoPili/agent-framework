package com.massimiliano.appmaze.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SoundManager singleton for managing game sound effects using SoundPool.
 *
 * Responsibilities:
 * - Load sound effects from res/raw/ on initialization
 * - Play sounds on demand with proper stream management
 * - Release resources on app termination
 * - Handle audio focus and attributes for game usage
 *
 * Sound events:
 * - PLAYER_MOVE: Short tick when player moves
 * - WALL_BUMP: Thud when player hits a wall
 * - HINT_REVEAL: Chime when hint is revealed
 * - GAME_COMPLETE: Victory fanfare when maze is completed
 * - BUTTON_CLICK: UI click sound for button presses
 *
 * Configuration:
 * - maxStreams: 5 (allows up to 5 simultaneous sounds)
 * - AudioAttributes: USAGE_GAME for proper audio routing
 */
object SoundManager {
    private const val TAG = "SoundManager"
    private const val MAX_STREAMS = 5

    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<SoundEvent, Int>()
    private var isInitialized = false

    /**
     * Enum representing all sound events in the game.
     */
    enum class SoundEvent {
        PLAYER_MOVE,
        WALL_BUMP,
        HINT_REVEAL,
        GAME_COMPLETE,
        BUTTON_CLICK,
    }

    /**
     * Initializes the SoundManager and loads all sound effects.
     * Must be called once during app startup (typically in MainActivity or Application class).
     *
     * @param context Application context for loading resources
     */
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "SoundManager already initialized")
            return@withContext
        }

        try {
            // Create AudioAttributes for game usage
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Create SoundPool with max 5 concurrent streams
            soundPool = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(audioAttributes)
                .build()

            // Load all sound effects
            loadSoundEffect(context, SoundEvent.PLAYER_MOVE, "player_move")
            loadSoundEffect(context, SoundEvent.WALL_BUMP, "wall_bump")
            loadSoundEffect(context, SoundEvent.HINT_REVEAL, "hint_reveal")
            loadSoundEffect(context, SoundEvent.GAME_COMPLETE, "game_complete")
            loadSoundEffect(context, SoundEvent.BUTTON_CLICK, "button_click")

            isInitialized = true
            Log.d(TAG, "SoundManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SoundManager", e)
            isInitialized = false
        }
    }

    /**
     * Loads a single sound effect from res/raw/.
     * Sound files should be named: {soundName}.wav or {soundName}.ogg
     *
     * @param context Application context
     * @param event The sound event to load
     * @param soundName The name of the sound file (without extension)
     */
    private fun loadSoundEffect(context: Context, event: SoundEvent, soundName: String) {
        try {
            val resourceId = context.resources.getIdentifier(
                soundName,
                "raw",
                context.packageName
            )

            if (resourceId == 0) {
                Log.w(TAG, "Sound resource not found: $soundName. Using placeholder.")
                // Store a placeholder ID (0) to gracefully handle missing files
                soundMap[event] = 0
                return
            }

            val soundId = soundPool?.load(context, resourceId, 1)
            if (soundId != null && soundId > 0) {
                soundMap[event] = soundId
                Log.d(TAG, "Loaded sound: $soundName (ID: $soundId)")
            } else {
                Log.w(TAG, "Failed to load sound: $soundName")
                soundMap[event] = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sound: $soundName", e)
            soundMap[event] = 0
        }
    }

    /**
     * Plays a sound effect if it has been loaded.
     * If the sound is not loaded (ID = 0), this call is silently ignored.
     *
     * @param event The sound event to play
     * @param volume Volume level (0.0f to 1.0f, default 1.0f)
     * @param pitch Pitch level (0.5f to 2.0f, default 1.0f)
     */
    fun playSound(
        event: SoundEvent,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        if (!isInitialized) {
            Log.w(TAG, "SoundManager not initialized. Call initialize() first.")
            return
        }

        val soundId = soundMap[event] ?: 0
        if (soundId == 0) {
            Log.d(TAG, "Sound not loaded or placeholder: $event")
            return
        }

        try {
            soundPool?.play(soundId, volume, volume, 1, 0, pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: $event", e)
        }
    }

    /**
     * Stops all currently playing sounds.
     */
    fun stopAllSounds() {
        try {
            soundPool?.autoPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sounds", e)
        }
    }

    /**
     * Resumes all paused sounds.
     */
    fun resumeAllSounds() {
        try {
            soundPool?.autoResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming sounds", e)
        }
    }

    /**
     * Releases all resources held by the SoundManager.
     * Must be called when the app is shutting down to prevent resource leaks.
     * After calling this, initialize() must be called again before playing sounds.
     */
    fun release() {
        try {
            soundPool?.release()
            soundPool = null
            soundMap.clear()
            isInitialized = false
            Log.d(TAG, "SoundManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing SoundManager", e)
        }
    }

    /**
     * Returns whether the SoundManager is currently initialized.
     */
    fun isReady(): Boolean = isInitialized && soundPool != null
}
