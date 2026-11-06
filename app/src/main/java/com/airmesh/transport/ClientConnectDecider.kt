package com.airmesh.transport

/**
 * Pure decision logic for the client's TCP connect loop, kept free of Android
 * so the should-retry / should-stop rules are unit-testable on the JVM with a
 * fake clock. The connector feeds it group and p2p state once per iteration
 * and acts on the returned [Decision]; all waiting happens in the connector.
 */
class ClientConnectDecider(private val clock: () -> Long = System::currentTimeMillis) {

    /** What the connect loop should do right now. */
    sealed interface Decision {
        /** The Wi-Fi Direct group is gone; stop the loop, no more attempts. */
        data object Stop : Decision

        /** Wait until [untilMs] before asking again, for [reason]. */
        data class Wait(val untilMs: Long, val reason: Reason) : Decision

        /** Group exists and a p2p address is present; run one attempt now. */
        data object Attempt : Decision
    }

    /** Why the loop should wait instead of attempting. */
    enum class Reason {
        /** Backoff after a failed connect attempt. */
        BACKOFF,

        /** No interface holds a 192.168.49.x address yet; an attempt would leave over the normal Wi-Fi network. */
        NO_P2P_ADDRESS,
    }

    private val policy = ReconnectPolicy(clock)

    /** Absolute time until which attempts are blocked by backoff, or null when none is pending. */
    private var retryAfterMs: Long? = null

    companion object {
        /** How long to wait while only the p2p address is missing (not a failure). */
        const val NO_P2P_ADDRESS_WAIT_MS = 1_000L
    }

    /** Asks what the loop should do now, given current group and p2p state. */
    fun decide(groupFormed: Boolean, p2pHasMeshAddress: Boolean): Decision = when {
        // A confirmed-gone group wins over everything else: no attempt may run
        // against a stale owner address, even if the backoff has elapsed.
        !groupFormed -> Decision.Stop

        (retryAfterMs ?: 0L) > clock() -> Decision.Wait(retryAfterMs!!, Reason.BACKOFF)

        !p2pHasMeshAddress -> Decision.Wait(clock() + NO_P2P_ADDRESS_WAIT_MS, Reason.NO_P2P_ADDRESS)

        else -> Decision.Attempt
    }

    /** Records a failed attempt; returns the backoff delay (ms) for logging. */
    fun recordFailure(): Long {
        val delayMs = policy.recordFailure()
        retryAfterMs = clock() + delayMs
        return delayMs
    }

    /** A successful connect clears the backoff schedule. */
    fun onSuccess() {
        policy.reset()
        retryAfterMs = null
    }

    /** Clears all state; call when starting a fresh loop for a new group owner. */
    fun reset() {
        policy.reset()
        retryAfterMs = null
    }
}
