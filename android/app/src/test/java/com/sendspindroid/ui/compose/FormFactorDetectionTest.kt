package com.sendspindroid.ui.compose

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.determineFormFactor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that determineFormFactor returns the correct FormFactor
 * for different window sizes and TV mode.
 *
 * WindowWidthSizeClass thresholds:
 * - Compact: < 600dp
 * - Medium: 600dp - 839dp
 * - Expanded: >= 840dp
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class FormFactorDetectionTest {

    @Test
    fun compactWidth_returnsPhone() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.PHONE, formFactor)
    }

    @Test
    fun mediumWidth_returnsTablet7() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(700.dp, 1000.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.TABLET_7, formFactor)
    }

    @Test
    fun expandedWidth_returnsTablet10() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(900.dp, 1200.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.TABLET_10, formFactor)
    }

    @Test
    fun tvDevice_alwaysReturnsTv_regardlessOfWindowSize() {
        val compactWindow = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
        val expandedWindow = WindowSizeClass.calculateFromSize(DpSize(1920.dp, 1080.dp))

        assertEquals(FormFactor.TV, determineFormFactor(compactWindow, isTv = true))
        assertEquals(FormFactor.TV, determineFormFactor(expandedWindow, isTv = true))
    }

    @Test
    fun exactBoundary_600dp_returnsMedium() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(600.dp, 800.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.TABLET_7, formFactor)
    }

    @Test
    fun exactBoundary_840dp_returnsExpanded() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(840.dp, 1200.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.TABLET_10, formFactor)
    }

    @Test
    fun narrowPhone_320dp_returnsPhone() {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(320.dp, 568.dp))
        val formFactor = determineFormFactor(windowSizeClass, isTv = false)
        assertEquals(FormFactor.PHONE, formFactor)
    }

    @Test
    fun formFactor_enum_containsAllExpectedValues() {
        val allValues = FormFactor.entries
        assertEquals(5, allValues.size)
        assert(allValues.contains(FormFactor.PHONE))
        assert(allValues.contains(FormFactor.TABLET_7))
        assert(allValues.contains(FormFactor.TABLET_10))
        assert(allValues.contains(FormFactor.TV))
        assert(allValues.contains(FormFactor.HEADUNIT))
    }
}
