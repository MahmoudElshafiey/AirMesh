package com.airmesh.service

/**
 * Tracks whether any AirMesh activity is currently visible, so [MeshService] can
 * decide if received content needs a notification (the user already sees it on
 * screen otherwise). Count-based: overlapping activities or a rotation
 * (stop + start) never drive the count below zero. Called only from the main
 * thread by activity lifecycle callbacks.
 */
object AppForeground {

    private var startedCount = 0

    fun activityStarted() { startedCount += 1 }

    fun activityStopped() { if (startedCount > 0) startedCount -= 1 }

    val anyActivityVisible: Boolean get() = startedCount > 0
}
