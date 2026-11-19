package com.airmesh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.chat.PresenceState
import com.airmesh.presence.PresenceState as LivePresenceState
import com.airmesh.ui.UiFlags
import com.airmesh.ui.theme.AirMeshSpacing
import com.airmesh.ui.theme.AirMeshTheme
import com.airmesh.ui.unreadBadgeVisible

/**
 * One row of the conversation list (TASK U1 part 1): avatar with presence, title and
 * preview on the left, time and unread badge on the right. Tapping it opens the
 * conversation. All colors come from the theme; text is single-line with ellipsis so
 * long names and messages never break the layout.
 */
@Composable
fun ConversationRow(
    title: String,
    colorIndex: Int,
    preview: String,
    timeText: String,
    unreadCount: Int,
    presence: PresenceState?,
    livePresence: LivePresenceState? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AirMeshSpacing.md, vertical = AirMeshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = title, colorIndex = colorIndex, size = 48.dp, presence = presence, livePresence = livePresence)
        Spacer(modifier = Modifier.width(AirMeshSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timeText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(AirMeshSpacing.sm))
                    Text(
                        text = timeText,
                        // TASK S3: the unread emphasis on the time label is gated by the flag.
                        color = if (UiFlags.SHOW_UNREAD_UI && unreadCount > 0) colors.primary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preview,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // TASK S3: the numeric badge is hidden while the flag is off.
                if (unreadBadgeVisible(UiFlags.SHOW_UNREAD_UI, unreadCount)) {
                    Spacer(modifier = Modifier.width(AirMeshSpacing.sm))
                    UnreadBadge(count = unreadCount)
                }
            }
        }
    }
}

@Preview(name = "ConversationRow · unread · light")
@Composable
private fun ConversationRowUnreadLightPreview() = AirMeshTheme(darkTheme = false) {
    ConversationRow(
        title = "Alice Smith",
        colorIndex = 1,
        preview = "Copy that. Sending the supply list next.",
        timeText = "12:41",
        unreadCount = 3,
        presence = PresenceState.ONLINE,
        onClick = {},
    )
}

@Preview(name = "ConversationRow · read · dark")
@Composable
private fun ConversationRowReadDarkPreview() = AirMeshTheme(darkTheme = true) {
    ConversationRow(
        title = "User #42718",
        colorIndex = 5,
        preview = "Location",
        timeText = "Yesterday",
        unreadCount = 0,
        presence = PresenceState.RECENT,
        onClick = {},
    )
}
