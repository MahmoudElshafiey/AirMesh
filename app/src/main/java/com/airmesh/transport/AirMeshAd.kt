package com.airmesh.transport

/**
 * One AirMesh advertisement seen on Wi-Fi Direct DNS-SD, or the one we publish.
 * The device address is part of the ad as a fallback: discovery callbacks do
 * carry the peer's WifiP2pDevice, but some devices report a placeholder MAC
 * there, and without an address we could see a phone but never connect to it.
 */
data class AirMeshAd(
    val deviceAddress: String,
    val nodeId: Int,
    val protocolVersion: Int,
    val isGroupOwner: Boolean,
)

/**
 * Encodes/decodes the ad token carried in the DNS-SD TXT record (under one key)
 * and as the instance name. The format is therefore restricted to what a DNS
 * label allows: lowercase hex MAC, digits and hyphens.
 *
 * Token layout: `airmesh-<mac>-n<nodeId>-v<version>-o<0|1>`
 */
object AirMeshAdCodec {

    /** The DNS-SD service type; only peers advertising this are AirMesh phones. */
    const val SERVICE_TYPE = "_airmesh._tcp"

    /** Bump when the token layout changes; old tokens simply fail to decode. */
    const val PROTOCOL_VERSION = 1

    private const val PREFIX = "airmesh"

    fun encode(deviceAddress: String, nodeId: Int, isGroupOwner: Boolean): String {
        val mac = deviceAddress.lowercase().replace(":", "")
        return "$PREFIX-$mac-n$nodeId-v$PROTOCOL_VERSION-o${if (isGroupOwner) 1 else 0}"
    }

    /** Decodes a token; null for anything that is not a well-formed AirMesh ad. */
    fun decode(raw: String?): AirMeshAd? {
        if (raw == null) return null
        val parts = raw.split("-")
        // airmesh | mac | n<id> | v<version> | o<flag> — exactly five parts.
        if (parts.size != 5 || parts[0] != PREFIX) return null

        val mac = parts[1]
        if (!isMacHex(mac)) return null

        val nodeId = parts[2].removePrefix("n").toIntOrNull() ?: return null
        // Node ids are assigned in 1..65535 (NodeIdProvider); anything else is garbage.
        if (nodeId !in 1..0xFFFF) return null

        val version = parts[3].removePrefix("v").toIntOrNull() ?: return null
        if (version != PROTOCOL_VERSION) return null

        val ownerFlag = parts[4]
        if (ownerFlag.length != 2 || ownerFlag[0] != 'o' || (ownerFlag[1] != '0' && ownerFlag[1] != '1')) return null

        // Canonical form matches WifiP2pDevice.deviceAddress: uppercase, colonized.
        return AirMeshAd(colonize(mac.uppercase()), nodeId, version, ownerFlag[1] == '1')
    }

    private fun isMacHex(s: String): Boolean =
        s.length == 12 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun colonize(hex: String): String = hex.chunked(2).joinToString(":")
}
