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

/** Overflow menu trigger in the chats top bar (Material "more_vert": three dots). */
val MeshMoreIcon = ImageVector.Builder(
    name = "MeshMoreIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Dots at (12,5), (12,12) and (12,19), radius 2.
        moveTo(14f, 5f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
        moveTo(14f, 12f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
        moveTo(14f, 19f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
    }
}.build()

/** "New message" FAB trigger (Material "edit" pencil). */
val MeshNewMessageIcon = ImageVector.Builder(
    name = "MeshNewMessageIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Pencil body.
        moveTo(3f, 17.25f)
        verticalLineTo(21f)
        horizontalLineToRelative(3.75f)
        lineTo(17.81f, 9.94f)
        lineToRelative(-3.75f, -3.75f)
        lineTo(3f, 17.25f)
        close()
        // Pencil tip.
        moveTo(20.71f, 7.04f)
        curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
        lineToRelative(-2.34f, -2.34f)
        curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
        lineToRelative(-1.83f, 1.83f)
        lineToRelative(3.75f, 3.75f)
        lineToRelative(1.83f, -1.83f)
        close()
    }
}.build()

/** Top-bar back arrow on detail screens (TASK U1 part 2; Material "arrow_back"). */
val MeshBackIcon = ImageVector.Builder(
    name = "MeshBackIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        verticalLineTo(11f)
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

/** Delivery state of an outgoing direct message still in the queue (Material "schedule" clock). */
val MeshClockIcon = ImageVector.Builder(
    name = "MeshClockIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // Ring: outer circle radius 10 at (12,12), inner radius 8 — EvenOdd cuts the hole.
        moveTo(22f, 12f)
        arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        close()
        moveTo(18f, 12f)
        arcToRelative(6f, 6f, 0f, true, true, -12f, 0f)
        arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
        close()
    }
    // Hands: 12 -> 3 (hour) and 12 -> 8-ish (minute), as filled wedges.
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 7f); horizontalLineToRelative(1.5f); verticalLineToRelative(5.5f); lineToRelative(-4.5f, 2.6f); verticalLineToRelative(-1.5f); close()
    }
}.build()

/** One tick: the message was handed to a neighbor (Material "check"). */
val MeshCheckIcon = ImageVector.Builder(
    name = "MeshCheckIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(9f, 16.17f)
        lineTo(4.83f, 12f)
        lineTo(3.41f, 13.41f)
        lineTo(9f, 19f)
        lineTo(21f, 7f)
        lineTo(19.59f, 5.59f)
        close()
    }
}.build()

/** Two ticks: the recipient's receipt arrived (Material "done_all"). */
val MeshDoubleCheckIcon = ImageVector.Builder(
    name = "MeshDoubleCheckIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // First tick.
        moveTo(18f, 7f)
        lineToRelative(-1.41f, -1.41f)
        lineToRelative(-6.34f, 6.34f)
        lineToRelative(1.41f, 1.41f)
        close()
        // Second tick (the longer one).
        moveTo(22.24f, 5.59f)
        lineTo(11.66f, 16.17f)
        lineTo(7.48f, 12f)
        lineToRelative(-1.41f, 1.41f)
        lineTo(11.66f, 19f)
        lineToRelative(12f, -12f)
        close()
        // Third tick (the short left one).
        moveTo(0.41f, 13.41f)
        lineTo(6f, 19f)
        lineToRelative(1.41f, -1.41f)
        lineTo(1.83f, 12f)
        close()
    }
}.build()

/** Outgoing message not delivered within the limit; tap to resend (Material "error" circle). */
val MeshAlertIcon = ImageVector.Builder(
    name = "MeshAlertIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // Ring: outer radius 10 at (12,12), inner radius 8.
        moveTo(22f, 12f)
        arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        close()
        moveTo(18f, 12f)
        arcToRelative(6f, 6f, 0f, true, true, -12f, 0f)
        arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
        close()
    }
    // Exclamation mark: stem + dot.
    path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 7f); horizontalLineToRelative(2f); verticalLineToRelative(6f); horizontalLineToRelative(-2f); close()
        moveTo(11f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
    }
}.build()

/** Dismiss button for the one-time "not encrypted" note (Material "close"). */
val MeshCloseIcon = ImageVector.Builder(
    name = "MeshCloseIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 6.41f)
        lineTo(17.59f, 5f)
        lineTo(12f, 10.59f)
        lineTo(6.41f, 5f)
        lineTo(5f, 6.41f)
        lineTo(10.59f, 12f)
        lineTo(5f, 17.59f)
        lineTo(6.41f, 19f)
        lineTo(12f, 13.41f)
        lineTo(17.59f, 19f)
        lineTo(19f, 17.59f)
        lineTo(13.41f, 12f)
        close()
    }
}.build()
