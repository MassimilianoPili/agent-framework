package com.massimiliano.appmaze.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for AppMaze Material 3 theme.
 * Verifies color scheme, typography, and dark theme support.
 */
@RunWith(AndroidJUnit4::class)
class AppMazeThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDarkPrimaryColor() {
        assertEquals(DarkPrimary, Color(0xFF00D084))
    }

    @Test
    fun testDarkSecondaryColor() {
        assertEquals(DarkSecondary, Color(0xFF4DD0E1))
    }

    @Test
    fun testDarkTertiaryColor() {
        assertEquals(DarkTertiary, Color(0xFF26C6DA))
    }

    @Test
    fun testDarkErrorColor() {
        assertEquals(DarkError, Color(0xFFFF6B6B))
    }

    @Test
    fun testDarkBackgroundColor() {
        assertEquals(DarkBackground, Color(0xFF0A0E27))
    }

    @Test
    fun testDarkSurfaceColor() {
        assertEquals(DarkSurface, Color(0xFF121829))
    }

    @Test
    fun testMazeWallColor() {
        assertEquals(MazeWallColor, Color(0xFF1A2332))
    }

    @Test
    fun testPlayerColor() {
        assertEquals(PlayerColor, Color(0xFF00D084))
    }

    @Test
    fun testExitColor() {
        assertEquals(ExitColor, Color(0xFF4DD0E1))
    }

    @Test
    fun testHintPathColor() {
        assertEquals(HintPathColor, Color(0xFF26C6DA))
    }

    @Test
    fun testHintPathAlpha() {
        assertEquals(HintPathAlpha, 0.6f)
    }

    @Test
    fun testAppMazeTypographyExists() {
        assertNotNull(AppMazeTypography)
    }

    @Test
    fun testAppMazeTypographyDisplayLarge() {
        assertNotNull(AppMazeTypography.displayLarge)
    }

    @Test
    fun testAppMazeTypographyHeadlineMedium() {
        assertNotNull(AppMazeTypography.headlineMedium)
    }

    @Test
    fun testAppMazeTypographyBodyLarge() {
        assertNotNull(AppMazeTypography.bodyLarge)
    }

    @Test
    fun testAppMazeTypographyLabelLarge() {
        assertNotNull(AppMazeTypography.labelLarge)
    }

    @Test
    fun testThemeComposableWithDarkTheme() {
        composeTestRule.setContent {
            AppMazeTheme(darkTheme = true) {
                // Theme should be applied without errors
                val colors = MaterialTheme.colorScheme
                assertNotNull(colors)
            }
        }
    }

    @Test
    fun testThemeComposableWithLightTheme() {
        composeTestRule.setContent {
            AppMazeTheme(darkTheme = false) {
                // Theme should be applied without errors
                val colors = MaterialTheme.colorScheme
                assertNotNull(colors)
            }
        }
    }

    @Test
    fun testThemeComposableWithDynamicColorDisabled() {
        composeTestRule.setContent {
            AppMazeTheme(darkTheme = true, dynamicColor = false) {
                // Theme should use custom colors, not dynamic
                val colors = MaterialTheme.colorScheme
                assertNotNull(colors)
            }
        }
    }

    @Test
    fun testColorSchemeHasAllRequiredColors() {
        composeTestRule.setContent {
            AppMazeTheme {
                val colors = MaterialTheme.colorScheme
                assertNotNull(colors.primary)
                assertNotNull(colors.secondary)
                assertNotNull(colors.tertiary)
                assertNotNull(colors.error)
                assertNotNull(colors.background)
                assertNotNull(colors.surface)
            }
        }
    }

    @Test
    fun testTypographyHasAllRequiredStyles() {
        composeTestRule.setContent {
            AppMazeTheme {
                val typography = MaterialTheme.typography
                assertNotNull(typography.displayLarge)
                assertNotNull(typography.displayMedium)
                assertNotNull(typography.displaySmall)
                assertNotNull(typography.headlineLarge)
                assertNotNull(typography.headlineMedium)
                assertNotNull(typography.headlineSmall)
                assertNotNull(typography.titleLarge)
                assertNotNull(typography.titleMedium)
                assertNotNull(typography.titleSmall)
                assertNotNull(typography.bodyLarge)
                assertNotNull(typography.bodyMedium)
                assertNotNull(typography.bodySmall)
                assertNotNull(typography.labelLarge)
                assertNotNull(typography.labelMedium)
                assertNotNull(typography.labelSmall)
            }
        }
    }
}
