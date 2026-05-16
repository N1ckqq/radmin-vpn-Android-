package com.radminvpn.android.model

/**
 * Represents a VPN Gate server fetched from the public API.
 */
data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val score: Int,
    val ping: Int,
    val speed: Long,           // bps
    val countryLong: String,
    val countryShort: String,
    val numVpnSessions: Int,
    val uptime: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val operator: String,
    val message: String,
    val openVpnConfigBase64: String  // Base64-encoded .ovpn config
) {
    val speedMbps: Double get() = speed / 1_000_000.0

    val countryFlag: String get() {
        if (countryShort.length != 2) return "\uD83C\uDF10"
        val first = Character.codePointAt(countryShort, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(countryShort, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    val isOnline: Boolean get() = ping > 0 && speed > 0 && openVpnConfigBase64.isNotEmpty()
}
