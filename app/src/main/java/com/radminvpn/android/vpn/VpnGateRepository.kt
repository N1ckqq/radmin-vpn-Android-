package com.radminvpn.android.vpn

import com.radminvpn.android.model.VpnGateServer
import com.radminvpn.android.util.VpnLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches live VPN server list from VPN Gate (University of Tsukuba, Japan).
 * API: http://www.vpngate.net/api/iphone/
 *
 * CSV columns (15):
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 * Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 */
object VpnGateRepository {

    private const val TAG = "VpnGate"

    private val API_URLS = listOf(
        "http://www.vpngate.net/api/iphone/",
        "https://www.vpngate.net/api/iphone/",
        "http://vpngate.net/api/iphone/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetch the server list. Returns sorted by score (best first).
     * Only returns servers that have OpenVPN config available.
     */
    suspend fun fetchServers(): Result<List<VpnGateServer>> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        for (url in API_URLS) {
            try {
                VpnLog.i(TAG, "Fetching servers from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "P2PVPN-Android/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    VpnLog.w(TAG, "HTTP ${response.code} from $url")
                    response.close()
                    continue
                }

                val body = response.body?.string() ?: ""
                response.close()

                if (body.isBlank()) {
                    VpnLog.w(TAG, "Empty response from $url")
                    continue
                }

                val servers = parseCsv(body)
                if (servers.isNotEmpty()) {
                    VpnLog.success(TAG, "Loaded ${servers.size} servers")
                    return@withContext Result.success(servers)
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Error fetching from $url: ${e.message}")
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Failed to fetch servers from all endpoints"))
    }

    private fun parseCsv(csv: String): List<VpnGateServer> {
        val servers = mutableListOf<VpnGateServer>()
        val lines = csv.lines()

        for (line in lines) {
            // Skip header lines (start with * or #) and empty lines
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue

            val columns = line.split(",")
            if (columns.size < 15) continue

            try {
                val configBase64 = columns[14].trim()
                // Only include servers with OpenVPN config
                if (configBase64.isEmpty() || configBase64 == "0") continue

                val server = VpnGateServer(
                    hostName = columns[0],
                    ip = columns[1],
                    score = columns[2].toIntOrNull() ?: 0,
                    ping = columns[3].toIntOrNull() ?: 0,
                    speed = columns[4].toLongOrNull() ?: 0L,
                    countryLong = columns[5],
                    countryShort = columns[6],
                    numVpnSessions = columns[7].toIntOrNull() ?: 0,
                    uptime = columns[8].toLongOrNull() ?: 0L,
                    totalUsers = columns[9].toLongOrNull() ?: 0L,
                    totalTraffic = columns[10].toLongOrNull() ?: 0L,
                    operator = columns[12],
                    message = columns[13],
                    openVpnConfigBase64 = configBase64
                )

                if (server.isOnline) {
                    servers.add(server)
                }
            } catch (e: Exception) {
                // Skip malformed rows
                continue
            }
        }

        // Sort by score descending (best servers first)
        return servers.sortedByDescending { it.score }
    }
}
