package com.airmesh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.airmesh.node.LogEntry
import com.airmesh.ui.theme.AirMeshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * TASK T1 part A: with 600 lines the capped log screen still displays rows and its list node
 * has a positive height (the old code rendered nothing above an empty filtered list).
 *
 * Instrumented — run on a device or emulator with:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
class MeshLogTabAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entries(count: Int): List<LogEntry> =
        (0 until count).map { LogEntry(timeMs = 1_700_000_000_000L + it, text = "line-%04d".format(it), seq = it.toLong()) }

    @Test
    fun cappedLogStillShowsRows() {
        compose.setContent {
            AirMeshTheme(dynamicColor = false) {
                MeshLogTab(events = entries(600), onClearLog = {})
            }
        }
        // Follow is ON by default, so the list settles at the bottom and the newest row shows.
        compose.onNodeWithText("line-0599", substring = true).assertExists()
        assertTrue(
            "at least one log row is displayed",
            compose.onAllNodesWithTag("logRow").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the list node has a positive height",
            compose.onNodeWithTag("logList").fetchSemanticsNode().size.height > 0,
        )
    }
}
