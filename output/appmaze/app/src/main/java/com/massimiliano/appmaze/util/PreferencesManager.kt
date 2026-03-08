package com.massimiliano.appmaze.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class PreferencesManager(private val context: Context) {
    companion object {
        private val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        private val DARK_MODE = booleanPreferencesKey("dark_mode")
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SOUND_ENABLED] ?: true }

    val hapticEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HAPTIC_ENABLED] ?: true }

    val darkMode: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DARK_MODE] ?: true }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SOUND_ENABLED] = enabled
        }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAPTIC_ENABLED] = enabled
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DARK_MODE] = enabled
        }
    }
}
