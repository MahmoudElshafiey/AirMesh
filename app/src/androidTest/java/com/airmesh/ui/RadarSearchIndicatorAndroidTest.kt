package com.airmesh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.airmesh.ui.components.RadarSearchIndicator
import com.airmesh.ui.theme.AirMeshTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the radar indicator (TASK U3 part 2). The main clock is frozen and
 * advanced manually, so "the phase moved" can only mean the frame clock really drove a redraw.
 */
class RadarSearchIndicatorAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // Freeze time; every test advances it explicitly.
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
    }

    /** The indicator publishes its drawn phase as "phase=0.xxx" on its semantics node. */
    private fun phaseOf(node: SemanticsNodeInteraction): Float {
        val description = node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        return description.removePrefix("phase=").toFloat()
    }

    @Test
    fun phaseAdvancesWhenTheClockAdvances() {
        compose.setContent {
            AirMeshTheme(dynamicColor = false) {
                RadarSearchIndicator(kind = EmptyStateKind.SEARCHING, periodMs = RADAR_SEARCHING_PERIOD_MS)
            }
        }
        val node = compose.onNodeWithTag("radarIndicator")
        assertEquals(0f, phaseOf(node), 0f)

        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()

        assertNotEquals(0f, phaseOf(node))
    }

    @Test
    fun parentRecompositionWithEqualArgsDoesNotResetPhase() {
        var tick by mutableIntStateOf(0)
        compose.setContent {
            AirMeshTheme(dynamicColor = false) {
                // The padding forces the parent to recompose when tick changes; the indicator's
                // own arguments stay equal, so its frame clock must keep running uninterrupted.
                Box(modifier = Modifier.padding(tick.dp)) {
                    RadarSearchIndicator(kind = EmptyStateKind.SEARCHING, periodMs = RADAR_SEARCHING_PERIOD_MS)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val before = phaseOf(compose.onNodeWithTag("radarIndicator"))

        tick++ // recomposes the parent with equal indicator args
        compose.waitForIdle()

        assertEquals(before, phaseOf(compose.onNodeWithTag("radarIndicator")), 0f)
    }

    @Test
    fun leavingAndReenteringCompositionStartsThePhaseAgain() {
        var visible by mutableStateOf(true)
        compose.setContent {
            AirMeshTheme(dynamicColor = false) {
                if (visible) {
                    RadarSearchIndicator(kind = EmptyStateKind.SEARCHING, periodMs = RADAR_SEARCHING_PERIOD_MS)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertTrue(phaseOf(compose.onNodeWithTag("radarIndicator")) > 0f)

        visible = false // leave composition: the frame clock is disposed with it
        compose.waitForIdle()
        compose.onAllNodesWithTag("radarIndicator").assertCountEquals(0)

        visible = true // re-enter: a fresh indicator starts at phase 0 (documented behavior)
        compose.waitForIdle()
        assertEquals(0f, phaseOf(compose.onNodeWithTag("radarIndicator")), 0f)
    }
}
