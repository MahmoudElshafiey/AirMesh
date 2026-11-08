package com.airmesh.transport

/**
 * Counters for the raw DNS-SD callbacks and the peer filter outcome.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM. All
 * callers — the two DNS-SD listeners and the 5 s discovery loop — run on the
 * main looper, so plain ints are enough.
 *
 * The counters are per report window: the peer directory prints
 * [statsLine] every 60 s and calls [reset], so each printed line describes
 * only the last minute instead of a total since app start.
 */
class DnsSdStats {

    private var scans = 0
    private var rawService = 0
    private var rawTxt = 0
    private var accepted = 0
    private var ignored = 0

    /** One discoverServices() issuance. */
    fun onScan() {
        scans++
    }

    /** One onDnsSdServiceAvailable callback, before any filtering. */
    fun onRawService() {
        rawService++
    }

    /** One onDnsSdTxtRecordAvailable callback, before any filtering. */
    fun onRawTxt() {
        rawTxt++
    }

    /** A callback that passed the filters and entered the peer list. */
    fun onAccepted() {
        accepted++
    }

    /** A callback dropped by the filters (no ad, own ad, no usable address). */
    fun onIgnored() {
        ignored++
    }

    /** The exact line logged once per report window. */
    fun statsLine(): String =
        "dnssd: stats scans=$scans rawService=$rawService rawTxt=$rawTxt accepted=$accepted ignored=$ignored"

    /** Total raw callbacks this window (service + TXT), reported alongside the strategy. */
    val rawCallbacks: Int get() = rawService + rawTxt

    /** Zero every counter, starting a new report window. */
    fun reset() {
        scans = 0
        rawService = 0
        rawTxt = 0
        accepted = 0
        ignored = 0
    }
}
