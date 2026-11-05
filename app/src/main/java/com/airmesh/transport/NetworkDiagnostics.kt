package com.airmesh.transport

import java.net.Inet4Address
import java.net.NetworkInterface

/** Snapshot of one network interface for diagnostics. */
data class InterfaceInfo(
    val name: String,
    val up: Boolean,
    val ipv4Addresses: List<String>,
)

/**
 * Turns the OS network-interface table into event-log lines.
 *
 * Kept free of Android imports so the flagging logic is unit-testable on the
 * JVM. On Android the Wi-Fi Direct link usually rides the regular wlan0
 * interface (the radio switches to P2P mode) rather than a dedicated p2p*
 * device, so besides naming an interface "p2p" we also flag whichever
 * interface holds a 192.168.49.x address — the standard group subnet.
 */
object NetworkDiagnostics {

    /** True when [name] looks like a Wi-Fi Direct (p2p) interface. */
    fun isP2pInterface(name: String): Boolean = "p2p" in name.lowercase()

    /** True when any address sits in the standard P2P group subnet 192.168.49.0/24. */
    fun holdsGroupAddress(ipv4Addresses: List<String>): Boolean =
        ipv4Addresses.any { it.startsWith("192.168.49.") }

    /** One log line per interface, flagging p2p interfaces and group-subnet addresses. */
    fun describe(interfaces: List<InterfaceInfo>): List<String> = interfaces.map { info ->
        val flags = buildList {
            if (isP2pInterface(info.name)) add("P2P")
            if (holdsGroupAddress(info.ipv4Addresses)) add("192.168.49.x")
        }
        val flagText = if (flags.isEmpty()) "" else " [${flags.joinToString(", ")}]"
        "${info.name} up=${info.up} ipv4=[${info.ipv4Addresses.joinToString(", ")}]$flagText"
    }

    /** Enumerates every interface with its IPv4 addresses. */
    fun enumerate(): List<InterfaceInfo> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { ni ->
            runCatching {
                InterfaceInfo(
                    name = ni.name,
                    up = ni.isUp,
                    ipv4Addresses = ni.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .toList(),
                )
            }.getOrNull()
        }
}
