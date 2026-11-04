package com.airmesh.sim

/**
 * Virtual clock for the deterministic simulator.
 *
 * Only [SimNetwork] advances it. Nothing in the simulation reads the real wall
 * clock, so a run is reproducible and completes as fast as the CPU allows.
 */
class SimClock(initialNow: Long = 0L) {

    /** Current simulated time in milliseconds. */
    var now: Long = initialNow
        private set

    internal fun advanceTo(time: Long) {
        require(time >= now) {
            "virtual time must not move backwards: now=$now, requested=$time"
        }
        now = time
    }
}
