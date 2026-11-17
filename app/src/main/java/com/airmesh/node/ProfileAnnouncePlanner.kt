package com.airmesh.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Decides when this node re-announces its PROFILE (TASK R2 part 1).
 *
 * Pure Kotlin: an injected [clock] and a seeded [Random] make every rule
 * deterministic in tests. The planner only schedules; the actual flood is the
 * caller's [onAnnounce], which reports whether a frame really went out so that
 * no-op announces (no name set yet) never consume cap budget or move the
 * periodic gate.
 *
 * Triggers:
 * 1. [onProfileSetOrChanged] — my profile was set or changed: announce now.
 * 2. [onNeighborUp] — a retry burst at [burstDelaysMs]. PROFILE frames are not
 *    stored, so without retries a lost first shot would wait for the periodic
 *    re-announce; the rest of one neighbor's burst is cancelled as soon as its
 *    own PROFILE arrives ([onProfileReceivedFrom]).
 * 3. [onNewOriginSeen] — traffic from an origin whose profile we do not know:
 *    one re-announce after a seeded jitter, at most once per cooldown, so a
 *    newcomer learns the existing members (and vice versa).
 * 4. [considerPeriodicAnnounce] — the historical periodic re-announce (default
 *    every 120 s), only while a neighbor is live; the node polls it on its
 *    replay cadence exactly as before.
 *
 * Every path goes through a sliding-window cap of [maxAnnouncesPerMinute] per
 * minute so bursts cannot flood the mesh.
 */
class ProfileAnnouncePlanner(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random(DEFAULT_RANDOM_SEED),
    private val onAnnounce: suspend () -> Boolean,
    private val burstDelaysMs: List<Long> = DEFAULT_BURST_DELAYS_MS,
    private val newOriginJitterRangeMs: LongRange = DEFAULT_NEW_ORIGIN_JITTER_RANGE_MS,
    private val newOriginCooldownMs: Long = 10_000L,
    /** Same value the node used before the planner existed (TASK R2 part 1). */
    private val periodicIntervalMs: Long = DEFAULT_PERIODIC_INTERVAL_MS,
    private val maxAnnouncesPerMinute: Int = MAX_ANNOUNCES_PER_MINUTE,
) {

    companion object {
        /** Fixed seed so jitter is reproducible in tests. */
        const val DEFAULT_RANDOM_SEED = 20_261_117L

        /** Periodic re-announce interval; keeps today's behavior as the default. */
        const val DEFAULT_PERIODIC_INTERVAL_MS = 120_000L

        /** Sliding window over which [MAX_ANNOUNCES_PER_MINUTE] is enforced. */
        const val CAP_WINDOW_MS = 60_000L

        /** Global announcement cap: at most this many PROFILE floods per minute. */
        const val MAX_ANNOUNCES_PER_MINUTE = 8

        /** Neighbor-up retry burst: a quick first shot, then widening retries. */
        val DEFAULT_BURST_DELAYS_MS: List<Long> = listOf(300L, 2_000L, 6_000L, 15_000L)

        /** Jitter window for the new-origin re-announce (TASK R2 part 1). */
        val DEFAULT_NEW_ORIGIN_JITTER_RANGE_MS: LongRange = 1_000L..3_000L
    }

    private val lock = Any()
    private val random = random

    /** Timestamps of the announces inside the current cap window. */
    private val announceTimes = ArrayDeque<Long>()

    /** Time of the last REAL announce; gates the periodic re-announce. */
    @Volatile private var lastAnnounceAt = 0L

    /** When the new-origin trigger last scheduled a re-announce (cooldown). */
    @Volatile private var lastNewOriginScheduledAt: Long? = null

    /** Pending burst job per neighbor that just came up. */
    private val burstJobs = HashMap<Int, Job>()

    /** Trigger 1: my profile was set or changed — announce right away. */
    suspend fun onProfileSetOrChanged() {
        cappedAnnounce()
    }

    /**
     * Trigger 2: [neighborId] just came up — schedule its retry burst at the
     * absolute offsets [burstDelaysMs] after this call. A repeat call (link flap)
     * restarts the burst from the beginning. Offsets are measured from the trigger,
     * not from the previous step, so a slow announce cannot drift the whole burst;
     * an already-overdue step is skipped rather than queued.
     */
    fun onNeighborUp(neighborId: Int) {
        synchronized(lock) { burstJobs[neighborId]?.cancel() }
        val job = scope.launch {
            val startAt = clock()
            for (delayMs in burstDelaysMs) {
                val remaining = delayMs - (clock() - startAt)
                if (remaining > 0L) delay(remaining)
                cappedAnnounce()
            }
        }
        synchronized(lock) { burstJobs[neighborId] = job }
    }

    /** Trigger 2 cancel: a PROFILE from [originId] arrived — its burst is done. */
    fun onProfileReceivedFrom(originId: Int) {
        val job = synchronized(lock) { burstJobs.remove(originId) }
        job?.cancel()
    }

    /**
     * Trigger 3: we saw traffic from an origin whose profile we do not know yet —
     * re-announce our own name once after a seeded jitter, at most once per
     * [newOriginCooldownMs], so the newcomer learns the existing members.
     */
    fun onNewOriginSeen(originId: Int) {
        val now = clock()
        synchronized(lock) {
            val last = lastNewOriginScheduledAt
            if (last != null && now - last < newOriginCooldownMs) return
            lastNewOriginScheduledAt = now
        }
        val jitterMs = random.nextLong(newOriginJitterRangeMs.first, newOriginJitterRangeMs.last + 1)
        scope.launch {
            delay(jitterMs)
            cappedAnnounce()
        }
    }

    /**
     * Trigger 4: the node's periodic tick — announce only while a neighbor is
     * live and [periodicIntervalMs] has passed since the last real announce.
     */
    suspend fun considerPeriodicAnnounce(hasLiveNeighbor: Boolean) {
        if (!hasLiveNeighbor) return
        val now = clock()
        if (now - lastAnnounceAt < periodicIntervalMs) return
        cappedAnnounce()
    }

    /**
     * Runs [onAnnounce] under the per-minute cap. A slot is reserved before the
     * call and released again when nothing was actually sent, so no-op announces
     * (no name yet) neither count against the cap nor move the periodic gate.
     */
    private suspend fun cappedAnnounce(): Boolean {
        val now = clock()
        synchronized(lock) {
            while (announceTimes.isNotEmpty() && now - announceTimes.first() >= CAP_WINDOW_MS) {
                announceTimes.removeFirst()
            }
            if (announceTimes.size >= maxAnnouncesPerMinute) return false
            announceTimes.addLast(now) // reserved; released below when nothing was sent
        }
        val announced = onAnnounce()
        synchronized(lock) {
            if (announced) {
                lastAnnounceAt = now
            } else if (announceTimes.isNotEmpty() && announceTimes.last() == now) {
                // Entries are appended in clock order, so our reserved slot is the
                // newest one with this timestamp; release it without touching any
                // older real announces.
                announceTimes.removeLast()
            }
        }
        return announced
    }
}
