package com.airmesh.transport

/**
 * Parsed AirMesh DNS-SD instance advertisement.
 *
 * Unlike [AirMeshAd] (internal codec with signed-Int tie-break and
 * strict 8-hex parsing), this data class uses [Long] for [tieBreak]
 * (unsigned 32-bit, 0..0xFFFFFFFF) and accepts 1–8 hex digits plus
 * common DNS-SD suffixes when parsing.
 */
data class AirMeshAdvert(
    val nodeId: Int,
    val version: Int,
    val tieBreak: Long,
    val owner: Boolean,
    /** Live client count a group owner advertises (0..99); 0 for clients / unknown. */
    val clients: Int = 0,
)

// ---- suffix table (ordered longest-first so the first match wins) --------

private val KNOWN_SUFFIXES = listOf(
    "._airmesh._tcp.local.",
    "._airmesh._tcp.local",
    ".local.",
    ".local",
)

// ---- core regex on the bare token (case-insensitive) ---------------------
// airmesh-n<nodeId>-v<version>-t<1..8 hex>-o<0|1>(-c<count>)?

private val INSTANCE_RE = Regex(
    """airmesh-n(\d{1,5})-v(\d+)-t([0-9a-f]{1,8})-o([01])(?:-c(\d+))?""",
    RegexOption.IGNORE_CASE,
)

// ---- prefix that the TXT-style full domain may carry ---------------------

private const val SERVICE_PREFIX = "_airmesh._tcp."

/**
 * Parses a DNS-SD instance name (as delivered by Android callbacks) into
 * an [AirMeshAdvert], stripping known suffixes and an optional
 * `_airmesh._tcp.` prefix. Case-insensitive.
 *
 * Accepted raw forms (all equivalent for `airmesh-n52647-v2-t7e1b99fe-o0`):
 * ```
 * airmesh-n52647-v2-t7e1b99fe-o0
 * airmesh-n52647-v2-t7e1b99fe-o0.local
 * airmesh-n52647-v2-t7e1b99fe-o0.local.
 * airmesh-n52647-v2-t7e1b99fe-o0._airmesh._tcp.local
 * airmesh-n52647-v2-t7e1b99fe-o0._airmesh._tcp.local.
 * _airmesh._tcp.airmesh-n52647-v2-t7e1b99fe-o0.local.
 * ```
 * An owner may append `-c<count>` (0..99) before the DNS suffix, e.g.
 * `airmesh-n52647-v2-t7e1b99fe-o1-c3.local.`; a missing or out-of-range count
 * parses as 0 / clamps to 99 respectively.
 *
 * Returns `null` for null, empty, missing parts, nodeId outside 1..65535,
 * owner flag other than 0 or 1, or extra garbage anywhere in the token.
 */
fun parseAirMeshInstance(raw: String?): AirMeshAdvert? {
    if (raw.isNullOrBlank()) return null

    var s = raw.trim()

    // 1. Strip known DNS suffixes (case-insensitive, longest first).
    for (suffix in KNOWN_SUFFIXES) {
        if (s.endsWith(suffix, ignoreCase = true)) {
            s = s.dropLast(suffix.length)
            break
        }
    }

    // 2. Strip a leading "_airmesh._tcp." prefix (TXT-style full domain).
    if (s.startsWith(SERVICE_PREFIX, ignoreCase = true)) {
        s = s.drop(SERVICE_PREFIX.length)
    }

    // 3. The remainder must be *exactly* the bare token — no extra chars.
    val m = INSTANCE_RE.matchEntire(s) ?: return null

    val nodeId = m.groupValues[1].toIntOrNull() ?: return null
    if (nodeId !in 1..0xFFFF) return null          // 1..65535

    val version = m.groupValues[2].toIntOrNull() ?: return null

    val hexStr = m.groupValues[3]
    val tieBreak = hexStr.lowercase().toLongOrNull(16) ?: return null
    // Already guaranteed ≤ 8 hex digits by the regex, so 0..0xFFFFFFFF.

    val ownerFlag = m.groupValues[4] == "1"

    // Optional -c<count> suffix: absent means 0 (old format / a client). A count
    // above the 2-digit field is clamped to 99 rather than rejected, so a newer
    // device advertising a big group still parses on this one.
    val rawClients = m.groupValues[5]
    val clients = if (rawClients.isEmpty()) {
        0
    } else {
        val v = rawClients.toLongOrNull()
        if (v == null) 99 else v.coerceIn(0, 99).toInt()
    }

    return AirMeshAdvert(nodeId, version, tieBreak, ownerFlag, clients)
}

/**
 * Builds the canonical bare instance name from an [AirMeshAdvert].
 * The tie-break hex is zero-padded to 8 digits to match the format
 * real devices produce. A non-zero client count appends `-c<count>` (clamped
 * to two digits); a count of 0 emits no suffix, so old-format names stay
 * byte-identical for clients and empty groups.
 *
 * Invariant: `parseAirMeshInstance(buildAirMeshInstance(x)) == x` for counts
 * in 0..99; counts above 99 round-trip as 99 (the field is two digits wide).
 */
fun buildAirMeshInstance(advert: AirMeshAdvert): String {
    require(advert.nodeId in 1..0xFFFF) { "nodeId out of range" }
    require(advert.tieBreak in 0..0xFFFFFFFFL) { "tieBreak out of range" }
    val hex = advert.tieBreak.toString(16).padStart(8, '0')
    val base = "airmesh-n${advert.nodeId}-v${advert.version}-t$hex-o${if (advert.owner) 1 else 0}"
    return if (advert.clients > 0) "$base-c${advert.clients.coerceIn(0, 99)}" else base
}

// ---- re-registration gate --------------------------------------------------

/** Minimum spacing between two client-count re-registrations of the local service. */
const val ADVERT_REREGISTER_MIN_INTERVAL_MS = 10_000L

/**
 * Pure gate for re-registering the owner's DNS-SD service after its live client
 * count changed: only a real change (newCount != lastCount) counts, and at most
 * one re-registration per [minIntervalMs]. `lastAtMs == 0` means "never
 * registered with a count yet", so the first real change always passes.
 */
fun shouldReRegisterAdvert(
    lastCount: Int,
    lastAtMs: Long,
    newCount: Int,
    nowMs: Long,
    minIntervalMs: Long = ADVERT_REREGISTER_MIN_INTERVAL_MS,
): Boolean =
    newCount != lastCount && (lastAtMs == 0L || nowMs - lastAtMs >= minIntervalMs)

/**
 * TASK T1 part D3: coalesces client-count re-registrations of the owner's DNS-SD service.
 *
 * The plain gate above fired on every real change as soon as the spacing had elapsed — a count
 * that oscillates (2 -> 3 -> 2 -> 3 ...) removed and re-added the local service on every flip,
 * which is exactly what DNS-SD does not like. Now a new value must be STABLE for [stableMs] before
 * it is registered; while it is pending, any other observed value restarts its stability clock (and
 * wins if it stabilizes first). Two actual registrations are still at least [minIntervalMs] apart
 * through [shouldReRegisterAdvert], so the service is never removed and re-added within one window.
 * Pure and fake-clock testable; the caller feeds it periodically (same values included) — stability
 * can only be observed while the count keeps being reported.
 */
class AdvertReregisterCoalescer(
    private val stableMs: Long = ADVERT_REREGISTER_MIN_INTERVAL_MS,
    private val minIntervalMs: Long = ADVERT_REREGISTER_MIN_INTERVAL_MS,
) {
    /** Count in the instance name currently registered (0..99). */
    var registeredCount: Int = 0
        private set

    /** New value waiting to become stable; -1 = nothing pending. */
    private var pendingCount: Int = -1
    private var pendingSinceMs: Long = 0L
    /** When the last actual re-registration happened; 0 = "never" (first change waits only stability). */
    private var lastRegisteredAtMs: Long = 0L

    /**
     * Feed one observation of the live client count. Returns true exactly when a new local-service
     * registration with [newCount] must happen now. A value equal to [registeredCount] clears any
     * pending change and returns false — an unchanged count never removes and re-adds the service.
     */
    fun onUpdate(newCount: Int, nowMs: Long): Boolean {
        val clamped = newCount.coerceIn(0, 99)
        if (clamped == registeredCount) {
            pendingCount = -1
            return false
        }
        if (pendingCount != clamped) {
            // A different value was seen: restart the stability clock for this one.
            pendingCount = clamped
            pendingSinceMs = nowMs
            return false
        }
        if (nowMs - pendingSinceMs < stableMs) return false
        // Stable long enough — still subject to the plain spacing gate between real registrations.
        if (!shouldReRegisterAdvert(registeredCount, lastRegisteredAtMs, clamped, nowMs, minIntervalMs)) {
            return false
        }
        registeredCount = clamped
        pendingCount = -1
        lastRegisteredAtMs = nowMs
        return true
    }

    /** A fresh registration just happened with [count] (startAdvertising): re-anchor the state. */
    fun onFreshRegistration(count: Int) {
        registeredCount = count.coerceIn(0, 99)
        pendingCount = -1
        // "Never" — matches today's lastClientReRegisterAtMs = 0 reset, so the first real change
        // after a fresh registration waits only for stability, not another full interval.
        lastRegisteredAtMs = 0L
    }

    /** stop(): forget everything. */
    fun reset() {
        registeredCount = 0
        pendingCount = -1
        pendingSinceMs = 0L
        lastRegisteredAtMs = 0L
    }
}
