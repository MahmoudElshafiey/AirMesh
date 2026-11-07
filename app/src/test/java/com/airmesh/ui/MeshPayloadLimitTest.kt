package com.airmesh.ui

import com.airmesh.protocol.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the compose-bar payload-limit helpers. */
class MeshPayloadLimitTest {

    @Test
    fun counterIsHiddenWellBelowTheLimit() {
        assertFalse(isNearPayloadLimit(0))
        assertFalse(isNearPayloadLimit(100))
        // Exactly at the margin boundary it is still hidden (strictly greater).
        assertFalse(isNearPayloadLimit(FrameCodec.MAX_PAYLOAD - 128))
    }

    @Test
    fun counterAppearsWithinTheWarningMargin() {
        assertTrue(isNearPayloadLimit(FrameCodec.MAX_PAYLOAD - 127))
        assertTrue(isNearPayloadLimit(FrameCodec.MAX_PAYLOAD - 1))
        assertTrue(isNearPayloadLimit(FrameCodec.MAX_PAYLOAD))
    }

    @Test
    fun counterHonorsACustomMaxPayload() {
        // maxPayload = 500: visible only above 500 - 128 = 372.
        assertFalse(isNearPayloadLimit(100, maxPayload = 500))
        assertFalse(isNearPayloadLimit(372, maxPayload = 500)) // boundary: not strictly greater
        assertTrue(isNearPayloadLimit(373, maxPayload = 500))
    }

    @Test
    fun counterTextShowsLengthOverLimit() {
        assertEquals("1024 / 1024 — near the frame payload limit", payloadLimitText(FrameCodec.MAX_PAYLOAD))
        assertEquals("900 / 1000 — near the frame payload limit", payloadLimitText(900, maxPayload = 1000))
    }
}
