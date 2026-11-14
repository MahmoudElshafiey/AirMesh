package com.airmesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.airmesh.ui.theme.LocalAirMeshColors

/** The state a [StatusChip] reflects; drives the leading dot color. */
enum class ChipTone { Neutral, Active, Warning }

/**
 * A compact tonal chip showing mesh status (TASK U1 part 1): a colored dot plus a
 * short label on a surfaceVariant pill. The label text is computed by pure helpers
 * elsewhere; this component only renders it and the tone's color.
 */
@Composable
fun StatusChip(
    text: String,
    tone: ChipTone = ChipTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dotColor = when (tone) {
        ChipTone.Active -> LocalAirMeshColors.current.presenceOnline
        ChipTone.Warning -> LocalAirMeshColors.current.warning
        ChipTone.Neutral -> colors.outline
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(text = text, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(name = "StatusChip · connected · light")
@Composable
private fun StatusChipActiveLightPreview() = AirMeshTheme(darkTheme = false) {
    StatusChip(text = "Connected · 3 phones", tone = ChipTone.Active)
}

@Preview(name = "StatusChip · searching · dark")
@Composable
private fun StatusChipWarningDarkPreview() = AirMeshTheme(darkTheme = true) {
    StatusChip(text = "Searching…", tone = ChipTone.Warning)
}

@Preview(name = "StatusChip · no group · light")
@Composable
private fun StatusChipNeutralLightPreview() = AirMeshTheme(darkTheme = false) {
    StatusChip(text = "No group", tone = ChipTone.Neutral)
}
