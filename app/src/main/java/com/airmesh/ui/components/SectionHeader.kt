package com.airmesh.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.ui.theme.AirMeshSpacing
import com.airmesh.ui.theme.AirMeshTheme

/**
 * A small-caps section label for the conversation list (TASK U1 part 1), e.g.
 * "Chats" or "People in your network". Muted color and tight top padding keep it a
 * quiet divider rather than a heading.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(start = AirMeshSpacing.md, top = AirMeshSpacing.lg, bottom = AirMeshSpacing.xs),
    )
}

@Preview(name = "SectionHeader · light")
@Composable
private fun SectionHeaderLightPreview() = AirMeshTheme(darkTheme = false) {
    SectionHeader("People in your network")
}

@Preview(name = "SectionHeader · dark")
@Composable
private fun SectionHeaderDarkPreview() = AirMeshTheme(darkTheme = true) {
    SectionHeader("Chats")
}
