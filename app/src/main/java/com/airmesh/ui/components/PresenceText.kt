package com.airmesh.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import com.airmesh.ui.presenceText
import com.airmesh.ui.theme.AirMeshTheme
import com.airmesh.ui.theme.LocalAirMeshColors

/**
 * Renders a node's presence line (TASK U1 part 1): "Online" in green, or a muted
 * "Last seen …". The wording comes from the pure [presenceText] helper; this only
 * picks the color. Renders nothing when there is no text to show.
 */
@Composable
fun PresenceText(
    presence: NodePresence,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val text = presenceText(presence, nowMs)
    if (text.isEmpty()) return
    Text(
        text = text,
        color = if (presence.state == PresenceState.ONLINE) LocalAirMeshColors.current.presenceOnline else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Preview(name = "PresenceText · online · light")
@Composable
private fun PresenceTextOnlineLightPreview() = AirMeshTheme(darkTheme = false) {
    PresenceText(presence = NodePresence(PresenceState.ONLINE, true), nowMs = 0L)
}

@Preview(name = "PresenceText · recent · dark")
@Composable
private fun PresenceTextRecentDarkPreview() = AirMeshTheme(darkTheme = true) {
    PresenceText(presence = NodePresence(PresenceState.RECENT, false), nowMs = 0L)
}
