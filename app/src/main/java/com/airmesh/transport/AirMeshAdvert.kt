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
)

// ---- suffix table (ordered longest-first so the first match wins) --------

private val KNOWN_SUFFIXES = listOf(
    "._airmesh._tcp.local.",
    "._airmesh._tcp.local",
    ".local.",
    ".local",
)

// ---- core regex on the bare token (case-insensitive) ---------------------
// airmesh-n<nodeId>-v<version>-t<1..8 hex>-o<0|1>

private val INSTANCE_RE = Regex(
    """airmesh-n(\d{1,5})-v(\d+)-t([0-9a-f]{1,8})-o([01])""",
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

    return AirMeshAdvert(nodeId, version, tieBreak, ownerFlag)
}

/**
 * Builds the canonical bare instance name from an [AirMeshAdvert].
 * The tie-break hex is zero-padded to 8 digits to match the format
 * real devices produce.
 *
 * Invariant: `parseAirMeshInstance(buildAirMeshInstance(x)) == x`.
 */
fun buildAirMeshInstance(advert: AirMeshAdvert): String {
    require(advert.nodeId in 1..0xFFFF) { "nodeId out of range" }
    require(advert.tieBreak in 0..0xFFFFFFFFL) { "tieBreak out of range" }
    val hex = advert.tieBreak.toString(16).padStart(8, '0')
    return "airmesh-n${advert.nodeId}-v${advert.version}-t$hex-o${if (advert.owner) 1 else 0}"
}
