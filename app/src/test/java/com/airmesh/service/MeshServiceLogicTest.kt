package com.airmesh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure service logic in [MeshServiceLogic]. */
class MeshServiceLogicTest {

    // --- runningNotificationText --------------------------------------------

    @Test
    fun `running text with zero neighbors says no neighbors`() {
        assertEquals("AirMesh is running · no neighbors", runningNotificationText(0))
    }

    @Test
    fun `running text with one neighbor uses singular form`() {
        assertEquals("AirMesh is running · 1 neighbor", runningNotificationText(1))
    }

    @Test
    fun `running text with many neighbors uses plural form and the count`() {
        assertEquals("AirMesh is running · 3 neighbors", runningNotificationText(3))
        assertEquals("AirMesh is running · 42 neighbors", runningNotificationText(42))
    }

    // --- NotificationThrottle -------------------------------------------------

    @Test
    fun `throttle allows the first update`() {
        val throttle = NotificationThrottle(minIntervalMs = 2_000)
        assertTrue(throttle.shouldPost(nowMs = 1_000))
    }

    @Test
    fun `throttle suppresses updates inside the interval`() {
        val throttle = NotificationThrottle(minIntervalMs = 2_000)
        assertTrue(throttle.shouldPost(nowMs = 1_000))
        assertFalse(throttle.shouldPost(nowMs = 1_500))
        assertFalse(throttle.shouldPost(nowMs = 2_999))
    }

    @Test
    fun `throttle allows an update once the interval has elapsed`() {
        val throttle = NotificationThrottle(minIntervalMs = 2_000)
        assertTrue(throttle.shouldPost(nowMs = 1_000))
        assertFalse(throttle.shouldPost(nowMs = 2_999))
        // Exactly at the boundary: now - last >= interval, so it passes.
        assertTrue(throttle.shouldPost(nowMs = 3_000))
    }

    @Test
    fun `throttle restarts its clock after a suppressed update`() {
        val throttle = NotificationThrottle(minIntervalMs = 2_000)
        assertTrue(throttle.shouldPost(nowMs = 1_000))
        assertFalse(throttle.shouldPost(nowMs = 1_500)) // suppressed: clock stays at 1_000
        assertTrue(throttle.shouldPost(nowMs = 3_000))   // measured from the last POST, not the probe
    }

    @Test
    fun `throttle reset allows an immediate update`() {
        val throttle = NotificationThrottle(minIntervalMs = 2_000)
        assertTrue(throttle.shouldPost(nowMs = 1_000))
        assertFalse(throttle.shouldPost(nowMs = 1_500))
        throttle.reset()
        assertTrue(throttle.shouldPost(nowMs = 1_600))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throttle rejects a negative interval`() {
        NotificationThrottle(minIntervalMs = -1)
    }

    // --- shouldPostMessageNotification ---------------------------------------

    @Test
    fun `message notification is suppressed while an activity is visible`() {
        assertFalse(shouldPostMessageNotification(activityVisible = true, notificationsEnabled = true))
    }

    @Test
    fun `message notification posts when nothing is visible and enabled`() {
        assertTrue(shouldPostMessageNotification(activityVisible = false, notificationsEnabled = true))
    }

    @Test
    fun `message notification is suppressed when the user turned it off`() {
        assertFalse(shouldPostMessageNotification(activityVisible = false, notificationsEnabled = false))
    }

    // --- messageNotificationContent / shortenForNotification ------------------

    @Test
    fun `text content shows sender id and the short text`() {
        val content = messageNotificationContent(IncomingKind.TEXT, senderId = 7, text = "hello")
        assertEquals("AirMesh · Node #7", content.title)
        assertEquals("hello", content.body)
    }

    @Test
    fun `location content describes the share without coordinates`() {
        val content = messageNotificationContent(IncomingKind.LOCATION, senderId = 3, text = "")
        assertEquals("AirMesh · Node #3", content.title)
        assertEquals("shared their location", content.body)
    }

    @Test
    fun `shorten trims surrounding whitespace`() {
        assertEquals("hi", shortenForNotification("  hi  "))
    }

    @Test
    fun `shorten leaves short text unchanged`() {
        val text = "a".repeat(NOTIFICATION_TEXT_MAX_CHARS)
        assertEquals(text, shortenForNotification(text))
    }

    @Test
    fun `shorten elides long text with an ellipsis at the limit`() {
        val text = "b".repeat(NOTIFICATION_TEXT_MAX_CHARS + 50)
        val shortened = shortenForNotification(text)
        assertEquals(NOTIFICATION_TEXT_MAX_CHARS, shortened.length)
        assertTrue(shortened.endsWith("…"))
    }

    @Test
    fun `shorten with a non-positive limit returns empty`() {
        assertEquals("", shortenForNotification("anything", maxChars = 0))
    }

    // --- reduceMeshServicePhase -----------------------------------------------

    @Test
    fun `start moves the service from not started to running`() {
        assertEquals(
            MeshServicePhase.RUNNING,
            reduceMeshServicePhase(MeshServicePhase.NOT_STARTED, ServiceEvent.Start),
        )
    }

    @Test
    fun `starting an already running service is a no-op (started twice)`() {
        assertEquals(
            MeshServicePhase.RUNNING,
            reduceMeshServicePhase(MeshServicePhase.RUNNING, ServiceEvent.Start),
        )
    }

    @Test
    fun `stop returns to not started from any phase`() {
        assertEquals(
            MeshServicePhase.NOT_STARTED,
            reduceMeshServicePhase(MeshServicePhase.RUNNING, ServiceEvent.Stop),
        )
        assertEquals(
            MeshServicePhase.NOT_STARTED,
            reduceMeshServicePhase(MeshServicePhase.NOT_STARTED, ServiceEvent.Stop),
        )
    }

    // --- shouldRemoveGroupOnStop ----------------------------------------------

    @Test
    fun `explicit user disconnect removes the group`() {
        assertTrue(shouldRemoveGroupOnStop(StopReason.USER_DISCONNECT))
    }

    @Test
    fun `notification stop action removes the group`() {
        assertTrue(shouldRemoveGroupOnStop(StopReason.NOTIFICATION_STOP))
    }

    @Test
    fun `service destruction removes the group`() {
        assertTrue(shouldRemoveGroupOnStop(StopReason.SERVICE_DESTROYED))
    }

    @Test
    fun `activity stop never removes the group`() {
        assertFalse(shouldRemoveGroupOnStop(StopReason.ACTIVITY_STOPPED))
    }

    @Test
    fun `screen off never removes the group`() {
        assertFalse(shouldRemoveGroupOnStop(StopReason.SCREEN_OFF))
    }
}
