package com.massimiliano.appmaze.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Data class representing audio and haptic settings.
 *
 * @param soundEnabled Whether sound effects are enabled
 * @param hapticEnabled Whether haptic feedback is enabled
 */
data class AudioHapticSettings(
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
)

/**
 * SettingsManager for managing audio and haptic preferences using DataStore.
 *
 * Responsibilities:
 * - Persist sound on/off toggle
 * - Persist haptic on/off toggle
 * - Provide reactive Flow for settings changes
 * - Provide suspend functions for updating settings
 *
 * Uses Android DataStore (successor to SharedPreferences) for type-safe, coroutine-based
 * preference management.
 */
class SettingsManager(private val context: Context) {
    companion object {
        private const val PREFERENCES_NAME = "audio_haptic_settings"
        private val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        private val HAPTIC_ENABLED_KEY = booleanPreferencesKey("haptic_enabled")
    }

    private val dataStore: DataStore<Preferences> = context.dataStore

    /**
     * Observes audio and haptic settings as a Flow.
     * Emits the current settings whenever they change.
     */
    val settingsFlow: Flow<AudioHapticSettings> = dataStore.data.map { preferences ->
        AudioHapticSettings(
            soundEnabled = preferences[SOUND_ENABLED_KEY] ?: true,
            hapticEnabled = preferences[HAPTIC_ENABLED_KEY] ?: true,
        )
    }

    /**
     * Updates the sound enabled setting.
     *
     * @param enabled Whether sound effects should be enabled
     */
    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SOUND_ENABLED_KEY] = enabled
        }
    }

    /**
     * Updates the haptic enabled setting.
     *
     * @param enabled Whether haptic feedback should be enabled
     */
    suspend fun setHapticEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAPTIC_ENABLED_KEY] = enabled
        }
    }

    /**
     * Resets all settings to their default values.
     */
    suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences[SOUND_ENABLED_KEY] = true
            preferences[HAPTIC_ENABLED_KEY] = true
        }
    }
}

// Extension property for easy access to DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_haptic_settings"
)
