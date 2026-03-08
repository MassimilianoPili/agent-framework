package com.massimiliano.appmaze.util

import android.content.Context
import android.media.SoundPool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for SoundManager singleton.
 *
 * Tests cover:
 * - Initialization with valid context
 * - Sound loading from resources
 * - Sound playback with volume/pitch control
 * - Graceful handling of missing sound files
 * - Resource cleanup on release
 * - Idempotent initialization
 */
class SoundManagerTest {

    private lateinit var mockContext: Context

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        // Reset singleton state before each test
        SoundManager.release()
    }

    @AfterEach
    fun tearDown() {
        SoundManager.release()
    }

    @Test
    fun `initialize with valid context loads sounds successfully`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        // Act
        SoundManager.initialize(mockContext)

        // Assert
        assert(SoundManager.isReady())
    }

    @Test
    fun `initialize is idempotent - calling twice does not reinitialize`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        // Act
        SoundManager.initialize(mockContext)
        val firstReady = SoundManager.isReady()
        SoundManager.initialize(mockContext)
        val secondReady = SoundManager.isReady()

        // Assert
        assert(firstReady && secondReady)
    }

    @Test
    fun `playSound with missing resource does not throw`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 0
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            SoundManager.playSound(SoundManager.SoundEvent.PLAYER_MOVE)
        }
    }

    @Test
    fun `playSound before initialization does not throw`() {
        // Act & Assert
        assertDoesNotThrow {
            SoundManager.playSound(SoundManager.SoundEvent.WALL_BUMP)
        }
    }

    @Test
    fun `stopAllSounds does not throw`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            SoundManager.stopAllSounds()
        }
    }

    @Test
    fun `resumeAllSounds does not throw`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            SoundManager.resumeAllSounds()
        }
    }

    @Test
    fun `release cleans up resources`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)
        assert(SoundManager.isReady())

        // Act
        SoundManager.release()

        // Assert
        assert(!SoundManager.isReady())
    }

    @Test
    fun `playSound with custom volume and pitch does not throw`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            SoundManager.playSound(
                SoundManager.SoundEvent.HINT_REVEAL,
                volume = 0.5f,
                pitch = 1.5f
            )
        }
    }

    @Test
    fun `all sound events can be played without error`() = runTest {
        // Arrange
        every { mockContext.resources.getIdentifier(any(), any(), any()) } returns 1
        every { mockContext.getSystemService(any()) } returns mockk(relaxed = true)

        SoundManager.initialize(mockContext)

        // Act & Assert
        SoundManager.SoundEvent.values().forEach { event ->
            assertDoesNotThrow {
                SoundManager.playSound(event)
            }
        }
    }
}
