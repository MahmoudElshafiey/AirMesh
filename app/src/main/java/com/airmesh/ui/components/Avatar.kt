package com.airmesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airmesh.chat.PresenceState
import com.airmesh.ui.avatarColor
import com.airmesh.ui.avatarInitials
import com.airmesh.ui.theme.AirMeshTheme
import com.airmesh.ui.theme.LocalAirMeshColors

/**
 * A circular avatar with the node's initials on its palette color (TASK U1 part 1).
 * Optionally shows a presence dot at the bottom-right: green for ONLINE, amber for
 * RECENT, neutral for UNKNOWN. Initials and fill come from the pure profile helpers
 * so they are emoji-safe and JVM-tested; only the presence dot uses theme colors.
 */
@Composable
fun Avatar(
    name: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    presence: PresenceState? = null,
) {
    // The fill is the user-chosen identity color (data, not a theme literal).
    val fill = Color(avatarColor(colorIndex))
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarInitials(name),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        if (presence != null) {
            val dotColor = when (presence) {
                PresenceState.ONLINE -> LocalAirMeshColors.current.presenceOnline
                PresenceState.RECENT -> LocalAirMeshColors.current.warning
                PresenceState.UNKNOWN -> MaterialTheme.colorScheme.outline
            }
            // A screen-colored ring separates the dot from the avatar edge.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(size * 0.26f).clip(CircleShape).background(dotColor))
            }
        }
    }
}

@Preview(name = "Avatar · online · light")
@Composable
private fun AvatarOnlineLightPreview() = AirMeshTheme(darkTheme = false) {
    Avatar(name = "Alice Smith", colorIndex = 1, size = 56.dp, presence = PresenceState.ONLINE)
}

@Preview(name = "Avatar · recent · dark")
@Composable
private fun AvatarRecentDarkPreview() = AirMeshTheme(darkTheme = true) {
    Avatar(name = "🚑 Rescue", colorIndex = 3, size = 56.dp, presence = PresenceState.RECENT)
}

@Preview(name = "Avatar · no presence · light")
@Composable
private fun AvatarNoPresenceLightPreview() = AirMeshTheme(darkTheme = false) {
    Avatar(name = "User #7", colorIndex = 5, size = 56.dp)
}
