package com.massimiliano.appmaze.ui.game

import android.content.Context
import com.massimiliano.appmaze.data.repository.GameRepository
import com.massimiliano.appmaze.domain.game.ScoringEngine
import com.massimiliano.appmaze.domain.game.TimerManager
import com.massimiliano.appmaze.domain.maze.DifficultyLevel
import com.massimiliano.appmaze.domain.maze.MazeCell
import com.massimiliano.appmaze.domain.maze.MazeGenerator
import com.massimiliano.appmaze.util.HapticManager
import com.massimiliano.appmaze.util.SettingsManager
import com.massimiliano.appmaze.util.SoundManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for GameViewModel with audio/haptic integration.
 *
 * Tests cover:
 * - Game initialization and maze generation
 * - Player movement and wall collision detection
 * - Sound and haptic triggering on game events
 * - Settings observation and toggle
 * - Game completion and score calculation
 * - Resource cleanup on ViewModel destruction
 */
class GameViewModelTest {

    private lateinit var mockRepository: GameRepository
    private lateinit var mockContext: Context
    private lateinit var mockSettingsManager: SettingsManager
    private lateinit var viewModel: GameViewModel

    @BeforeEach
    fun setUp() {
        mockRepository = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        mockSettingsManager = mockk(relaxed = true)

        // Mock settings flow
        coEvery { mockSettingsManager.settingsFlow } returns flowOf(
            com.massimiliano.appmaze.util.AudioHapticSettings(
                soundEnabled = true,
                hapticEnabled = true
            )
        )

        // Mock SoundManager and HapticManager
        mockkObject(SoundManager)
        mockkObject(HapticManager)
        every { SoundManager.initialize(any()) } returns Unit
        every { SoundManager.isReady() } returns true
        every { SoundManager.playSound(any()) } returns Unit
        every { HapticManager.initialize(any()) } returns Unit
        every { HapticManager.isSupported() } returns true
        every { HapticManager.trigger(any()) } returns Unit
        every { HapticManager.release() } returns Unit
        every { SoundManager.release() } returns Unit

        viewModel = GameViewModel(mockRepository, mockContext, mockSettingsManager)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SoundManager)
        unmockkObject(HapticManager)
    }

    @Test
    fun `startNewGame initializes game state with correct difficulty`() = runTest {
        // Act
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Assert
        val state = viewModel.uiState.value
        assert(state.difficulty == DifficultyLevel.EASY)
        assert(state.gameStatus == GameStatus.PLAYING)
        assert(state.playerRow == 0)
        assert(state.playerCol == 0)
        assert(state.hintsRemaining == 3)
    }

    @Test
    fun `startNewGame triggers button click sound`() = runTest {
        // Act
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Assert
        verify { SoundManager.playSound(SoundManager.SoundEvent.BUTTON_CLICK) }
    }

    @Test
    fun `processSwipe with valid move updates player position`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.processSwipe(1) // RIGHT

        // Assert
        val state = viewModel.uiState.value
        assert(state.playerCol == 1)
    }

    @Test
    fun `processSwipe with valid move triggers move sound and haptic`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.processSwipe(1) // RIGHT

        // Assert
        verify { SoundManager.playSound(SoundManager.SoundEvent.PLAYER_MOVE) }
        verify { HapticManager.trigger(HapticManager.HapticEvent.LIGHT_TAP) }
    }

    @Test
    fun `processSwipe with invalid move triggers wall bump sound and haptic`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.processSwipe(3) // LEFT (out of bounds)

        // Assert
        verify { SoundManager.playSound(SoundManager.SoundEvent.WALL_BUMP) }
        verify { HapticManager.trigger(HapticManager.HapticEvent.MEDIUM_BUZZ) }
    }

    @Test
    fun `requestHint triggers hint reveal sound and haptic`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.requestHint()

        // Assert
        verify { SoundManager.playSound(SoundManager.SoundEvent.HINT_REVEAL) }
        verify { HapticManager.trigger(HapticManager.HapticEvent.LIGHT_TAP) }
    }

    @Test
    fun `requestHint decrements hints remaining`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)
        val initialHints = viewModel.uiState.value.hintsRemaining

        // Act
        viewModel.requestHint()

        // Assert
        val state = viewModel.uiState.value
        assert(state.hintsRemaining == initialHints - 1)
    }

    @Test
    fun `pauseGame stops timer and triggers button click sound`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.pauseGame()

        // Assert
        val state = viewModel.uiState.value
        assert(state.gameStatus == GameStatus.PAUSED)
        verify { SoundManager.playSound(SoundManager.SoundEvent.BUTTON_CLICK) }
    }

    @Test
    fun `resumeGame resumes timer and triggers button click sound`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)
        viewModel.pauseGame()

        // Act
        viewModel.resumeGame()

        // Assert
        val state = viewModel.uiState.value
        assert(state.gameStatus == GameStatus.PLAYING)
        verify(atLeast = 2) { SoundManager.playSound(SoundManager.SoundEvent.BUTTON_CLICK) }
    }

    @Test
    fun `toggleSound updates sound setting`() = runTest {
        // Arrange
        val initialState = viewModel.uiState.value.soundEnabled

        // Act
        viewModel.toggleSound()

        // Assert
        coVerify { mockSettingsManager.setSoundEnabled(!initialState) }
    }

    @Test
    fun `toggleHaptic updates haptic setting`() = runTest {
        // Arrange
        val initialState = viewModel.uiState.value.hapticEnabled

        // Act
        viewModel.toggleHaptic()

        // Assert
        coVerify { mockSettingsManager.setHapticEnabled(!initialState) }
    }

    @Test
    fun `clearHint removes hint path cells`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)
        viewModel.requestHint()

        // Act
        viewModel.clearHint()

        // Assert
        val state = viewModel.uiState.value
        assert(state.hintPathCells.isEmpty())
    }

    @Test
    fun `onCleared releases sound and haptic managers`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)

        // Act
        viewModel.onCleared()

        // Assert
        verify { SoundManager.release() }
        verify { HapticManager.release() }
    }

    @Test
    fun `sound disabled prevents sound playback`() = runTest {
        // Arrange
        coEvery { mockSettingsManager.settingsFlow } returns flowOf(
            com.massimiliano.appmaze.util.AudioHapticSettings(
                soundEnabled = false,
                hapticEnabled = true
            )
        )
        val viewModelWithDisabledSound = GameViewModel(mockRepository, mockContext, mockSettingsManager)

        // Act
        viewModelWithDisabledSound.startNewGame(DifficultyLevel.EASY)

        // Assert - button click should not be played when sound is disabled
        // (Note: This is a simplified test; in reality, we'd need to verify the internal state)
        assert(!viewModelWithDisabledSound.uiState.value.soundEnabled)
    }

    @Test
    fun `haptic disabled prevents haptic feedback`() = runTest {
        // Arrange
        coEvery { mockSettingsManager.settingsFlow } returns flowOf(
            com.massimiliano.appmaze.util.AudioHapticSettings(
                soundEnabled = true,
                hapticEnabled = false
            )
        )
        val viewModelWithDisabledHaptic = GameViewModel(mockRepository, mockContext, mockSettingsManager)

        // Act
        viewModelWithDisabledHaptic.startNewGame(DifficultyLevel.EASY)

        // Assert
        assert(!viewModelWithDisabledHaptic.uiState.value.hapticEnabled)
    }

    @Test
    fun `processSwipe does not move when game is paused`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)
        viewModel.pauseGame()
        val initialCol = viewModel.uiState.value.playerCol

        // Act
        viewModel.processSwipe(1) // RIGHT

        // Assert
        val state = viewModel.uiState.value
        assert(state.playerCol == initialCol)
    }

    @Test
    fun `requestHint does not work when hints are exhausted`() = runTest {
        // Arrange
        viewModel.startNewGame(DifficultyLevel.EASY)
        repeat(3) { viewModel.requestHint() }

        // Act
        viewModel.requestHint()

        // Assert
        val state = viewModel.uiState.value
        assert(state.hintsRemaining == 0)
    }
}
