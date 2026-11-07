package com.airmesh.node

/** Provider name used by [LocationCandidate.source]; matches LocationManager's "gps"/"network". */
const val LOCATION_GPS_PROVIDER = "gps"

/** Same, for the network provider (cell/Wi-Fi based; fixes indoors). */
const val LOCATION_NETWORK_PROVIDER = "network"

/**
 * Last-known fixes up to this old may still be shared — flagged as cached so
 * the UI can label them. Deliberately wider than [LOCATION_STALE_AFTER_MS]: in
 * a disaster scenario a 9-minute-old ±35 m fix is far more useful than no
 * position at all, and its age is shown instead of hidden.
 */
const val LOCATION_LAST_KNOWN_MAX_AGE_MS = 10 * 60 * 1000L

/** One observed candidate fix; [source] is the provider that produced it. */
data class LocationCandidate(
    val source: String,
    val latitudeE6: Int,
    val longitudeE6: Int,
    val accuracyMeters: Int,
    val timeMs: Long,
) {
    /** How old this fix is at [nowMs]; negative (clock skew) counts as fresh. */
    fun ageMs(nowMs: Long): Long = nowMs - timeMs

    /** The shareable form of this candidate. */
    fun toFix(): LocationFix = LocationFix(latitudeE6, longitudeE6, accuracyMeters, timeMs)
}

/** What the app may read from the device's location system. */
enum class LocationPermissionState { NONE, APPROXIMATE_ONLY, PRECISE }

/**
 * Everything the selection policy needs to decide; all times are epoch ms and
 * [nowMs] is injectable so tests run on a fake clock.
 */
data class LocationAttemptSnapshot(
    val nowMs: Long,
    /** How long this attempt waited for live fixes before deciding. */
    val waitedMs: Long = 0L,
    val permission: LocationPermissionState = LocationPermissionState.PRECISE,
    val locationServicesEnabled: Boolean = true,
    val gpsProviderEnabled: Boolean = true,
    val networkProviderEnabled: Boolean = true,
    /** Live fix received from GPS during this attempt's wait (null = none). */
    val liveGps: LocationCandidate? = null,
    /** Live fix received from the network provider during this attempt. */
    val liveNetwork: LocationCandidate? = null,
    val lastKnownGps: LocationCandidate? = null,
    val lastKnownNetwork: LocationCandidate? = null,
)

/** Outcome of [selectLocationFix]. */
sealed interface LocationDecision {
    /** Share [candidate]; [cached] means it came from last-known storage. */
    data class Use(val candidate: LocationCandidate, val cached: Boolean) : LocationDecision

    /** Nothing usable; [reasons] are the specific causes, shown to the user and logged. */
    data class Fail(val reasons: List<String>) : LocationDecision
}

/**
 * Pure selection policy for a share attempt — no Android imports, so it is
 * unit-testable on the JVM with a fake clock.
 *
 * Priority (never an accuracy threshold — a worse-but-available fix is shared
 * and its accuracy shown, never silently dropped):
 * 1. permission must be precise and location services on, else fail fast;
 * 2. a live GPS fix from this attempt;
 * 3. a live network-provider fix from this attempt (fixes indoors);
 * 4. the newest last-known fix up to [LOCATION_LAST_KNOWN_MAX_AGE_MS] old —
 *    flagged as cached so the UI can label it with its age.
 */
fun selectLocationFix(snapshot: LocationAttemptSnapshot): LocationDecision {
    when (snapshot.permission) {
        LocationPermissionState.NONE ->
            return LocationDecision.Fail(listOf("location permission not granted"))
        LocationPermissionState.APPROXIMATE_ONLY ->
            return LocationDecision.Fail(
                listOf("only approximate location allowed — allow precise location to share"),
            )
        LocationPermissionState.PRECISE -> Unit
    }
    if (!snapshot.locationServicesEnabled) {
        return LocationDecision.Fail(listOf("location services are turned off"))
    }

    // Tier 1: fresh fixes from this attempt. GPS first — it is the most
    // accurate source we have, so a live GPS fix beats a live network one.
    snapshot.liveGps?.let { return LocationDecision.Use(it, cached = false) }
    snapshot.liveNetwork?.let { return LocationDecision.Use(it, cached = false) }

    // Tier 2: last-known fixes up to the sharing window old; newest wins.
    val known = listOfNotNull(snapshot.lastKnownGps, snapshot.lastKnownNetwork)
        .filter { it.ageMs(snapshot.nowMs) <= LOCATION_LAST_KNOWN_MAX_AGE_MS }
    known.maxByOrNull { it.timeMs }?.let { return LocationDecision.Use(it, cached = true) }

    // Tier 3: nothing usable — say exactly why, per provider.
    val reasons = buildList {
        if (!snapshot.gpsProviderEnabled) add("GPS provider is turned off")
        else add("no GPS signal in ${snapshot.waitedMs / 1000}s — try near a window or outside")
        if (!snapshot.networkProviderEnabled) add("network location provider is turned off")
        else {
            val newestKnown = listOfNotNull(snapshot.lastKnownGps, snapshot.lastKnownNetwork)
                .maxByOrNull { it.timeMs }
            if (newestKnown != null) {
                add("last known ${newestKnown.source} fix too old (${formatAgeMs(newestKnown.ageMs(snapshot.nowMs))})")
            } else {
                add("no recent network or GPS fix")
            }
        }
    }
    return LocationDecision.Fail(reasons)
}

/** "42 s" below a minute, whole minutes above ("3 min"). */
fun formatAgeMs(ageMs: Long): String =
    if (ageMs < 60_000L) "${ageMs / 1000} s" else "${ageMs / 60_000} min"
