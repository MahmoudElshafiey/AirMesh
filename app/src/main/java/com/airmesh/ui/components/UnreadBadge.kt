package com.airmesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.ui.theme.AirMeshTheme
import com.airmesh.ui.unreadBadgeText

/**
 * A small pill showing the unread count (TASK U1 part 1). Hidden when there is
 * nothing to show; capped at "99+" by [unreadBadgeText]. Uses the brand primary so
 * it reads as an action cue in both themes.
 */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val text = unreadBadgeText(count)
    if (text.isEmpty()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Preview(name = "UnreadBadge · 3 · light")
@Composable
private fun UnreadBadgeLightPreview() = AirMeshTheme(darkTheme = false) {
    UnreadBadge(count = 3)
}

@Preview(name = "UnreadBadge · 99+ · dark")
@Composable
private fun UnreadBadgeDarkPreview() = AirMeshTheme(darkTheme = true) {
    UnreadBadge(count = 120)
}
