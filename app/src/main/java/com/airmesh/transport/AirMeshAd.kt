package com.airmesh.transport

/**
 * One AirMesh advertisement seen on Wi-Fi Direct DNS-SD, or the one we publish.
 * The ad carries no MAC: since Android 10 a normal app cannot read its own P2P
 * address (the system reports a placeholder), and peers are addressed by the
 * source device of the DNS-SD callback instead. The tie-break token makes the
 * group election symmetric even if two phones ever share a node id.
 */
data class AirMeshAd(
    val nodeId: Int,
    val protocolVersion: Int,
    val tieBreakToken: Int,
    val isGroupOwner: Boolean,
)

/**
 * Encodes/decodes the ad token carried in the DNS-SD TXT record (under one key)
 * and as the instance name. The format is restricted to what a DNS label allows:
 * digits, lowercase hex and hyphens.
 *
 * Token layout: `airmesh-n<nodeId>-v<version>-t<8-hex tie-break token>-o<0|1>`
 */
object AirMeshAdCodec {

    /** The DNS-SD service type; only peers advertising this are AirMesh phones. */
    const val SERVICE_TYPE = "_airmesh._tcp"

    /** Bump when the token layout changes; old tokens simply fail to decode. */
    const val PROTOCOL_VERSION = 2

    private const val PREFIX = "airmesh"

    fun encode(nodeId: Int, tieBreakToken: Int, isGroupOwner: Boolean): String {
        // The token is a signed Int but travels as an unsigned 8-hex value.
        val hex = tieBreakToken.toUInt().toString(16).padStart(8, '0')
        return "$PREFIX-n$nodeId-v$PROTOCOL_VERSION-t$hex-o${if (isGroupOwner) 1 else 0}"
    }

    /** Decodes a token; null for anything that is not a well-formed AirMesh ad. */
    fun decode(raw: String?): AirMeshAd? {
        if (raw == null) return null
        val parts = raw.split("-")
        // airmesh | n<id> | v<version> | t<hex> | o<flag> — exactly five parts.
        if (parts.size != 5 || parts[0] != PREFIX) return null

        val nodeId = parts[1].removePrefix("n").toIntOrNull() ?: return null
        // Node ids are assigned in 1..65535 (NodeIdProvider); anything else is garbage.
        if (nodeId !in 1..0xFFFF) return null

        val version = parts[2].removePrefix("v").toIntOrNull() ?: return null
        if (version != PROTOCOL_VERSION) return null

        val tokenHex = parts[3].removePrefix("t")
        // Exactly eight lowercase hex digits; anything else is not our ad.
        if (!isTokenHex(tokenHex)) return null
        val tieBreakToken = tokenHex.toUInt(16).toInt()

        val ownerFlag = parts[4]
        if (ownerFlag.length != 2 || ownerFlag[0] != 'o' || (ownerFlag[1] != '0' && ownerFlag[1] != '1')) return null

        return AirMeshAd(nodeId, version, tieBreakToken, ownerFlag[1] == '1')
    }

    private fun isTokenHex(s: String): Boolean =
        s.length == 8 && s.all { it in '0'..'9' || it in 'a'..'f' }
}
