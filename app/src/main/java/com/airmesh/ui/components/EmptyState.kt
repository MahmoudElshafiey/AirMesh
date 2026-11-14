package com.airmesh.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.ui.theme.AirMeshSpacing
import com.airmesh.ui.theme.AirMeshTheme

/**
 * A centered empty-state (TASK U1 part 1): a simple mesh illustration, a title and an
 * optional subtitle. When [animate] is true the illustration gently pulses to signal
 * that the app is still looking for nearby phones — subtle enough not to distract.
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    animate: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    // A slow alpha pulse; the transition always runs but only shows when [animate].
    val transition = rememberInfiniteTransition(label = "emptyPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AirMeshSpacing.sm),
    ) {
        Icon(
            imageVector = EmptyMeshIllustration,
            contentDescription = null,
            tint = colors.outline.copy(alpha = if (animate) pulse else 1f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(AirMeshSpacing.xs))
        Text(text = title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A small cluster of three nodes joined by links — the "mesh" at a glance. */
private val EmptyMeshIllustration = ImageVector.Builder(
    name = "EmptyMeshIllustration",
    defaultWidth = 96.dp,
    defaultHeight = 96.dp,
    viewportWidth = 96f,
    viewportHeight = 96f,
).apply {
    // Three nodes in a triangle — the "mesh" at a glance. Opaque black on purpose so
    // Icon's tint recolors them (a transparent brush would survive the tint blend unchanged).
    path(fill = SolidColor(Color.Black)) {
        // Node A (30,32) r=10.
        moveTo(40f, 32f); arcToRelative(10f, 10f, 0f, true, true, -20f, 0f); arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        // Node B (68,24) r=10.
        moveTo(78f, 24f); arcToRelative(10f, 10f, 0f, true, true, -20f, 0f); arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        // Node C (56,68) r=10.
        moveTo(66f, 68f); arcToRelative(10f, 10f, 0f, true, true, -20f, 0f); arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
    }
}.build()

@Preview(name = "EmptyState · static · light")
@Composable
private fun EmptyStateStaticLightPreview() = AirMeshTheme(darkTheme = false) {
    EmptyState(title = "No conversations yet", subtitle = "Phones you meet will appear here.")
}

@Preview(name = "EmptyState · searching · dark")
@Composable
private fun EmptyStateSearchingDarkPreview() = AirMeshTheme(darkTheme = true) {
    EmptyState(title = "Looking for phones nearby…", animate = true)
}
