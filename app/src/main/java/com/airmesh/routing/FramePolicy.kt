package com.airmesh.routing

import com.airmesh.protocol.Frame

/**
 * Per-frame policy hooks for the [Router] (TASK D1 part 2).
 *
 * The router always applies its normal rules first — dedup, local delivery of
 * user content, TTL-based forwarding and store-and-forward. A policy can only
 * veto, never add:
 *
 *  - [shouldForward] = false: the frame is neither forwarded nor stored; it
 *    still delivers locally if it is deliverable content (e.g. a direct message
 *    addressed to this node).
 *  - [shouldStore] = false: a frame that would be queued for store-and-forward
 *    is dropped instead, whether it was received or originated here.
 *
 * Pure Kotlin so the policy stays unit-testable on the JVM; the node supplies
 * one (see AirMeshNode) and everything else uses [Default].
 */
interface FramePolicy {

    /** False: this frame stops at this node — no forwarding, no queueing. */
    fun shouldForward(frame: Frame): Boolean = true

    /** False: a frame that would be stored for later replay is dropped instead. */
    fun shouldStore(frame: Frame): Boolean = true

    /** The historical behavior: forward and store everything the TTL allows. */
    object Default : FramePolicy
}
