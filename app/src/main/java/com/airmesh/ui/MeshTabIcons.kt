package com.airmesh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons hand-crafted as [ImageVector]s: the app has no material-icons
 * dependency, and adding one for a handful of glyphs is not worth it. All use
 * the standard 24dp viewport. Fills/strokes are opaque black on purpose — that
 * is what generated Material icons do too — so Icon's tint can recolor them (a
 * transparent brush would survive the tint blend unchanged).
 */

val MeshChatIcon = ImageVector.Builder(
    name = "MeshChatIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Filled speech bubble (Material "chat" shape).
    path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 2f)
        horizontalLineTo(4f)
        curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
        verticalLineToRelative(18f)
        lineToRelative(4f, -4f)
        horizontalLineToRelative(14f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(4f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
    }
}.build()

val MeshNetworkIcon = ImageVector.Builder(
    name = "MeshNetworkIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Three nodes (filled circles at (5,12), (19,5), (19,19)) joined by two
    // links — a tiny mesh. Link endpoints sit on the circle edges so the
    // strokes do not poke through the nodes.
    path(fill = SolidColor(Color.Black)) {
        // Circle centered at (5,12), radius 3.
        moveTo(8f, 12f)
        arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
        close()
        // Circle centered at (19,5), radius 3.
        moveTo(22f, 5f)
        arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
        close()
        // Circle centered at (19,19), radius 3.
        moveTo(22f, 19f)
        arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
        close()
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.68f, 10.66f)
        lineTo(16.32f, 6.34f)
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.68f, 13.34f)
        lineTo(16.32f, 17.66f)
    }
}.build()

val MeshLogIcon = ImageVector.Builder(
    name = "MeshLogIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Four log lines, the last one shorter (Material "subject" shape).
    path(fill = SolidColor(Color.Black)) {
        moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(7f); horizontalLineTo(4f); close()
        moveTo(4f, 9f); horizontalLineTo(20f); verticalLineTo(11f); horizontalLineTo(4f); close()
        moveTo(4f, 13f); horizontalLineTo(20f); verticalLineTo(15f); horizontalLineTo(4f); close()
        moveTo(4f, 17f); horizontalLineTo(14f); verticalLineTo(19f); horizontalLineTo(4f); close()
    }
}.build()

/** Compose-bar trigger for "share my location" (Material "place" pin). */
val MeshLocationPinIcon = ImageVector.Builder(
    name = "MeshLocationPinIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Teardrop pin with a round hole; EvenOdd cuts the hole out of the fill.
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 2f)
        curveTo(8.13f, 2f, 5f, 5.13f, 5f, 9f)
        curveTo(5f, 14.25f, 12f, 22f, 12f, 22f)
        curveTo(12f, 22f, 19f, 14.25f, 19f, 9f)
        curveTo(19f, 5.13f, 15.87f, 2f, 12f, 2f)
        close()
        // Hole: circle centered at (12,9), radius 2.5 — two half-arcs.
        moveTo(14.5f, 9f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, -5f, 0f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f)
        close()
    }
}.build()

/** Compose-bar send trigger (Material "send" paper plane). */
val MeshSendIcon = ImageVector.Builder(
    name = "MeshSendIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(2.01f, 21f)
        lineTo(23f, 12f)
        lineTo(2.01f, 3f)
        lineTo(2f, 10f)
        lineToRelative(15f, 2f)
        lineToRelative(-15f, 2f)
        close()
    }
}.build()
