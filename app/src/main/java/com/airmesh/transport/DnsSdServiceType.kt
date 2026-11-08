package com.airmesh.transport

/**
 * Service-type matching for the DNS-SD callbacks.
 *
 * The framework hands back the type in several shapes (`_airmesh._tcp`,
 * `_airmesh._tcp.local.`, sometimes uppercase), so every comparison goes
 * through [normalizeServiceType] first. Pure Kotlin (no Android imports) so it
 * is unit-testable on the JVM.
 */

/** The service type the mesh advertises; also the canonical normalized form. */
const val AIRMESH_SERVICE_TYPE = "_airmesh._tcp"

/**
 * Lowercase, trim, and strip trailing dots and a trailing ".local", so
 * `"_AirMesh._TCP.local."` becomes [AIRMESH_SERVICE_TYPE]. Null becomes "".
 */
fun normalizeServiceType(raw: String?): String {
    if (raw == null) return ""
    var type = raw.trim().lowercase()
    // "a.b.local." -> "a.b.local" -> "a.b" (the final trim covers "...local..").
    type = type.trimEnd('.')
    type = type.removeSuffix(".local")
    return type.trimEnd('.')
}

/**
 * True when the callback belongs to AirMesh: either the service type is ours,
 * or the instance name carries the mesh prefix. The prefix check is a fallback
 * for devices that report a mangled/empty type.
 */
fun isAirMeshService(type: String?, instanceName: String?): Boolean {
    if (normalizeServiceType(type) == AIRMESH_SERVICE_TYPE) return true
    return instanceName?.trim()?.lowercase()?.startsWith("airmesh-") == true
}
