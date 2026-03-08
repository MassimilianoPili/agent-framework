package com.massimiliano.appmaze.util

import android.content.Context
import android.os.Vibrator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for HapticManager utility.
 *
 * Tests cover:
 * - Initialization with valid context
 * - Haptic feedback triggering on supported devices
 * - Graceful degradation on devices without vibrator
 * - Resource cleanup on release
 * - All haptic event types
 */
class HapticManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockVibrator: Vibrator

    @BeforeEach
    fun setUp() {
        mockVibrator = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        every { mockVibrator.hasVibrator() } returns true
        every { mockContext.getSystemService(Context.VIBRATOR_SERVICE) } returns mockVibrator
        HapticManager.release()
    }

    @AfterEach
    fun tearDown() {
        HapticManager.release()
    }

    @Test
    fun `initialize with vibrator support sets isSupported to true`() {
        // Arrange
        every { mockVibrator.hasVibrator() } returns true

        // Act
        HapticManager.initialize(mockContext)

        // Assert
        assert(HapticManager.isSupported())
    }

    @Test
    fun `initialize without vibrator support sets isSupported to false`() {
        // Arrange
        every { mockVibrator.hasVibrator() } returns false

        // Act
        HapticManager.initialize(mockContext)

        // Assert
        assert(!HapticManager.isSupported())
    }

    @Test
    fun `trigger light tap does not throw`() {
        // Arrange
        HapticManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            HapticManager.trigger(HapticManager.HapticEvent.LIGHT_TAP)
        }
    }

    @Test
    fun `trigger medium buzz does not throw`() {
        // Arrange
        HapticManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            HapticManager.trigger(HapticManager.HapticEvent.MEDIUM_BUZZ)
        }
    }

    @Test
    fun `trigger success pattern does not throw`() {
        // Arrange
        HapticManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            HapticManager.trigger(HapticManager.HapticEvent.SUCCESS_PATTERN)
        }
    }

    @Test
    fun `trigger on unsupported device does not throw`() {
        // Arrange
        every { mockVibrator.hasVibrator() } returns false
        HapticManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            HapticManager.trigger(HapticManager.HapticEvent.LIGHT_TAP)
        }
    }

    @Test
    fun `trigger before initialization does not throw`() {
        // Act & Assert
        assertDoesNotThrow {
            HapticManager.trigger(HapticManager.HapticEvent.MEDIUM_BUZZ)
        }
    }

    @Test
    fun `cancel does not throw`() {
        // Arrange
        HapticManager.initialize(mockContext)

        // Act & Assert
        assertDoesNotThrow {
            HapticManager.cancel()
        }
    }

    @Test
    fun `release cleans up resources`() {
        // Arrange
        HapticManager.initialize(mockContext)
        assert(HapticManager.isSupported())

        // Act
        HapticManager.release()

        // Assert
        assert(!HapticManager.isSupported())
    }

    @Test
    fun `all haptic events can be triggered without error`() {
        // Arrange
        HapticManager.initialize(mockContext)

        // Act & Assert
        HapticManager.HapticEvent.values().forEach { event ->
            assertDoesNotThrow {
                HapticManager.trigger(event)
            }
        }
    }
}
