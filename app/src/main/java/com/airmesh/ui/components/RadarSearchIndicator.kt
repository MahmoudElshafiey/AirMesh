package com.airmesh.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.airmesh.ui.EmptyStateKind
import com.airmesh.ui.RADAR_SEARCHING_PERIOD_MS
import com.airmesh.ui.REDUCED_MOTION_RING_PHASES
import com.airmesh.ui.RingState
import com.airmesh.ui.isReducedMotion
import com.airmesh.ui.radarPhase
import com.airmesh.ui.ring
import com.airmesh.ui.ringAt
import kotlinx.coroutines.delay

/** The indicator is a fixed 160dp square; the rings expand to its full radius. */
private val RADAR_SIZE = 160.dp
private const val RING_COUNT = 3
private val CENTER_NODE_RADIUS = 14.dp
private val RING_STROKE_WIDTH = 3.dp

/** Self-check log lines are throttled to at most one shown/disposed pair per 10 s. */
private const val SELF_CHECK_INTERVAL_MS = 10_000L
private const val PHASE_CHECK_DELAY_MS = 2_000L

@Volatile
private var lastSelfCheckAtMs = 0L

/** Phases observed in the draw lambda (plain fields — no snapshot, so no extra invalidation). */
private class PhaseProbe {
    var first = Float.NaN
    var latest = Float.NaN
    var loggedShown = false
}

/**
 * Animated radar for the chats empty state (TASK U3 part 2): a center node with three
 * concentric rings expanding and fading out, staggered by a third of the period.
 *
 * Driven by a single frame clock ([produceState] + [withFrameMillis]) whose
 * value is read ONLY inside the Canvas draw lambda — a draw-phase read. Each frame redraws the
 * canvas without recomposing anything, so parent recompositions (e.g. the 1 Hz nowMs tick) can
 * neither restart nor freeze it: the phase is a pure function of the frame time. The only
 * parameters are stable values ([kind], [periodMs]); nothing here is keyed by per-second state.
 *
 * When the system animation scale is off, a deliberately composed static frame (rings at
 * phases 0.15 / 0.5 / 0.85) is drawn instead and no clock runs at all.
 */
@Composable
fun RadarSearchIndicator(
    kind: EmptyStateKind,
    periodMs: Long = RADAR_SEARCHING_PERIOD_MS,
    modifier: Modifier = Modifier,
    /** Self-check lines for the event log; never used as a composition key. */
    logEvent: (String) -> Unit = {},
) {
    require(kind == EmptyStateKind.SEARCHING || kind == EmptyStateKind.CONNECTING) {
        "RadarSearchIndicator only animates SEARCHING and CONNECTING; render a static empty state for other kinds"
    }
    val color = MaterialTheme.colorScheme.primary
    val glyphColor = MaterialTheme.colorScheme.onPrimary
    val reducedMotion = rememberReducedMotion()

    // Mirrors the last drawn phase into the accessibility node (and the instrumented tests).
    // Written from the draw lambda; it converges to a stable value each frame, so no loop.
    val drawnPhase = remember { mutableFloatStateOf(0f) }
    val probe = remember { PhaseProbe() }

    Box(
        modifier = modifier
            .size(RADAR_SIZE)
            .testTag("radarIndicator")
            .semantics { stateDescription = "phase=${"%.3f".format(drawnPhase.floatValue)}" },
    ) {
        if (reducedMotion) {
            // Deliberately composed static frame — no clock at all.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRadar(color, glyphColor, REDUCED_MOTION_RING_PHASES.map(::ringAt))
            }
        } else {
            // withFrameMillis suspends until the next frame and reports its time; looping it is
            // the infinite frame clock. The reported millis are monotonically increasing, which
            // is all radarPhase needs (it only ever takes t mod period).
            val frameTimeMs by produceState(initialValue = 0L) {
                while (true) { withFrameMillis { value = it } }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val phase = radarPhase(frameTimeMs, periodMs)
                if (probe.first.isNaN()) probe.first = phase
                probe.latest = phase
                drawnPhase.floatValue = phase
                drawRadar(color, glyphColor, List(RING_COUNT) { ring(phase, it, RING_COUNT) })
            }
        }
    }

    // Self-check in the event log (Log tab): "shown" on entry, a phase check 2 s later, and
    // "disposed" on leave — a restart loop would show up as repeated shown/disposed pairs. The
    // gate keeps it to at most one pair per 10 s so a pathological loop cannot spam the log.
    LaunchedEffect(kind) {
        val now = System.currentTimeMillis()
        if (now - lastSelfCheckAtMs >= SELF_CHECK_INTERVAL_MS) {
            lastSelfCheckAtMs = now
            probe.loggedShown = true
            logEvent("ui: indicator shown kind=$kind reduced=$reducedMotion")
            if (!reducedMotion) {
                delay(PHASE_CHECK_DELAY_MS)
                val moved = !probe.first.isNaN() && !probe.latest.isNaN() && probe.latest != probe.first
                logEvent(
                    if (moved) "ui: indicator phase moved (OK)" else "ui: WARNING indicator phase NOT advancing",
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Only pair a "disposed" with a "shown" this instance actually logged.
            if (probe.loggedShown) logEvent("ui: indicator disposed")
        }
    }
}

/**
 * The deliberately composed static radar frame (rings at [REDUCED_MOTION_RING_PHASES]) for the
 * empty-state kinds that do not animate (TASK U4 part 1): the exact same drawing code as the
 * reduced-motion branch, so a quiet state never falls back to plain dots. No clock runs here.
 */
@Composable
internal fun RadarStaticFrame(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val glyphColor = MaterialTheme.colorScheme.onPrimary
    Canvas(
        modifier = modifier.size(RADAR_SIZE),
    ) {
        drawRadar(color, glyphColor, REDUCED_MOTION_RING_PHASES.map(::ringAt))
    }
}

/**
 * Thin wrapper over Settings.Global.ANIMATOR_DURATION_SCALE. Any read failure falls back to the
 * default scale of 1f — we must never report reduced motion by accident.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        isReducedMotion(
            try {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            } catch (_: Exception) {
                // Unreadable for any reason → treat as the default scale.
                1f
            },
        )
    }
}

/**
 * Draws one expanding ring per [rings] entry, then the center node with its wifi-ish glyph.
 * Internal (not private) so the design-time previews can render fixed phases with the exact
 * same drawing code as the live indicator.
 */
internal fun DrawScope.drawRadar(
    color: Color,
    glyphColor: Color,
    rings: List<RingState>,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxRadius = size.minDimension / 2f

    for (ring in rings) {
        drawCircle(
            color = color.copy(alpha = ring.alpha),
            radius = maxRadius * ring.radiusFraction,
            center = center,
            style = Stroke(width = RING_STROKE_WIDTH.toPx()),
        )
    }

    // Center node: filled circle with a small wifi glyph (dot + two arcs) in onPrimary.
    val nodeRadius = CENTER_NODE_RADIUS.toPx()
    drawCircle(color = color, radius = nodeRadius, center = center)
    val s = nodeRadius / 12f // glyph scale relative to the node
    val dotCenter = Offset(center.x, center.y + 4f * s)
    drawCircle(color = glyphColor, radius = 1.5f * s, center = dotCenter)
    for (arcRadius in floatArrayOf(3.5f * s, 6.5f * s)) {
        drawArc(
            color = glyphColor,
            startAngle = 225f, // top-left …
            sweepAngle = 90f, // … through the top to top-right: the wifi fan
            useCenter = false,
            topLeft = Offset(dotCenter.x - arcRadius, dotCenter.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = 1.4f * s, cap = StrokeCap.Round),
        )
    }
}
