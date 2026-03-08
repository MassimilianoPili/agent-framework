package com.massimiliano.appmaze.ui.screens.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.massimiliano.appmaze.data.db.entity.GameScoreEntity
import com.massimiliano.appmaze.data.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Leaderboard screen.
 * Manages loading and clearing game scores from the database.
 *
 * @param gameRepository Repository for accessing game scores
 */
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val gameRepository: GameRepository,
) : ViewModel() {

    private val _easyScores = MutableStateFlow<List<GameScoreEntity>>(emptyList())
    val easyScores: StateFlow<List<GameScoreEntity>> = _easyScores.asStateFlow()

    private val _mediumScores = MutableStateFlow<List<GameScoreEntity>>(emptyList())
    val mediumScores: StateFlow<List<GameScoreEntity>> = _mediumScores.asStateFlow()

    private val _hardScores = MutableStateFlow<List<GameScoreEntity>>(emptyList())
    val hardScores: StateFlow<List<GameScoreEntity>> = _hardScores.asStateFlow()

    private val _expertScores = MutableStateFlow<List<GameScoreEntity>>(emptyList())
    val expertScores: StateFlow<List<GameScoreEntity>> = _expertScores.asStateFlow()

    /**
     * Loads top scores for all difficulty levels from the database.
     */
    fun loadScores() {
        viewModelScope.launch {
            try {
                _easyScores.value = gameRepository.getTopScoresByDifficulty("EASY", 10)
                _mediumScores.value = gameRepository.getTopScoresByDifficulty("MEDIUM", 10)
                _hardScores.value = gameRepository.getTopScoresByDifficulty("HARD", 10)
                _expertScores.value = gameRepository.getTopScoresByDifficulty("EXPERT", 10)
            } catch (e: Exception) {
                // Log error and keep empty lists
                e.printStackTrace()
            }
        }
    }

    /**
     * Clears all game scores from the database.
     * Reloads the leaderboard after clearing.
     */
    fun clearAllScores() {
        viewModelScope.launch {
            try {
                gameRepository.deleteAllScores()
                // Reload empty lists
                _easyScores.value = emptyList()
                _mediumScores.value = emptyList()
                _hardScores.value = emptyList()
                _expertScores.value = emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
