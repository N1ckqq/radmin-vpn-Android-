package com.radminvpn.android.vpn

import com.radminvpn.android.model.VpnGateServer
import com.radminvpn.android.util.VpnLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Fetches live VPN server list from VPN Gate (University of Tsukuba, Japan).
 * Includes multiple mirrors so the API works even when vpngate.net is blocked.
 *
 * CSV columns (15):
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 * Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 */
object VpnGateRepository {

    private const val TAG = "VpnGate"

    // Main URLs + mirrors for access without VPN
    private val API_URLS = listOf(
        "http://www.vpngate.net/api/iphone/",
        "https://www.vpngate.net/api/iphone/",
        "http://vpngate.net/api/iphone/",
        // Mirrors for blocked regions
        "http://public-vpn.com/api/iphone/",
        "https://vpngate-mirrors.github.io/api/iphone/",
        "http://103.47.78.180/api/iphone/",
        "http://219.100.37.27/api/iphone/",
        "http://219.100.37.18/api/iphone/",
        "http://219.100.37.24/api/iphone/"
    )

    // User-added custom mirror URLs
    private val customMirrors = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Add a custom mirror URL for accessing VPN Gate API
     */
    fun addCustomMirror(url: String) {
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"
        if (normalizedUrl !in customMirrors) {
            customMirrors.add(normalizedUrl)
            VpnLog.i(TAG, "Added custom mirror: $normalizedUrl")
        }
    }

    /**
     * Remove a custom mirror URL
     */
    fun removeCustomMirror(url: String) {
        customMirrors.remove(url)
        VpnLog.i(TAG, "Removed custom mirror: $url")
    }

    /**
     * Get all current mirror URLs (built-in + custom)
     */
    fun getAllMirrors(): List<String> {
        return API_URLS + customMirrors
    }

    /**
     * Get only custom mirrors
     */
    fun getCustomMirrors(): List<String> {
        return customMirrors.toList()
    }

    /**
     * Set custom mirrors from settings
     */
    fun setCustomMirrors(mirrors: List<String>) {
        customMirrors.clear()
        customMirrors.addAll(mirrors)
    }

    /**
     * Ping a server by attempting TCP connection
     * Returns latency in ms, or -1 on failure
     */
    suspend fun pingServer(ip: String, port: Int = 443): Int = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 5000)
            val latency = (System.currentTimeMillis() - startTime).toInt()
            socket.close()
            VpnLog.i(TAG, "Ping to $ip: ${latency}ms")
            latency
        } catch (e: Exception) {
            VpnLog.w(TAG, "Ping failed for $ip: ${e.message}")
            -1
        }
    }

    /**
     * Ping multiple servers in parallel
     */
    suspend fun pingAllServers(servers: List<VpnGateServer>): Map<String, Int> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Int>()
        for (server in servers) {
            val latency = pingServer(server.ip)
            results[server.ip] = latency
        }
        results
    }

    /**
     * Check which mirrors are accessible
     */
    suspend fun checkMirrorAvailability(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        val allUrls = API_URLS + customMirrors

        for (url in allUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "P2PVPN-Android/1.0")
                    .build()

                val response = client.newCall(request).execute()
                results[url] = response.isSuccessful
                response.close()
            } catch (e: Exception) {
                results[url] = false
            }
        }
        results
    }

    /**
     * Fetch the server list. Returns sorted by score (best first).
     * Tries all URLs (built-in + custom mirrors) until one succeeds.
     * Only returns servers that have OpenVPN config available.
     */
    suspend fun fetchServers(): Result<List<VpnGateServer>> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val allUrls = API_URLS + customMirrors

        for (url in allUrls) {
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
                    VpnLog.success(TAG, "Loaded ${servers.size} servers from $url")
                    return@withContext Result.success(servers)
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Error fetching from $url: ${e.message}")
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Failed to fetch servers from all endpoints"))
    }

    /**
     * Fetch servers filtered by country
     */
    suspend fun fetchServersByCountry(countryCode: String): Result<List<VpnGateServer>> {
        val result = fetchServers()
        return result.map { servers ->
            servers.filter { it.countryShort.equals(countryCode, ignoreCase = true) }
        }
    }

    /**
     * Get best server by score
     */
    suspend fun getBestServer(): Result<VpnGateServer> {
        val result = fetchServers()
        return result.mapCatching { servers ->
            servers.maxByOrNull { it.score }
                ?: throw Exception("No servers available")
        }
    }

    /**
     * Get best server by ping (lowest latency)
     */
    suspend fun getBestServerByPing(): Result<VpnGateServer> {
        val result = fetchServers()
        return result.mapCatching { servers ->
            servers.filter { it.ping > 0 }
                .minByOrNull { it.ping }
                ?: throw Exception("No servers with ping data available")
        }
    }

    /**
     * Get best server by speed
     */
    suspend fun getBestServerBySpeed(): Result<VpnGateServer> {
        val result = fetchServers()
        return result.mapCatching { servers ->
            servers.maxByOrNull { it.speed }
                ?: throw Exception("No servers available")
        }
    }

    private fun parseCsv(csv: String): List<VpnGateServer> {
        val servers = mutableListOf<VpnGateServer>()
        val lines = csv.lines()

        for (line in lines) {
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue

            val columns = line.split(",")
            if (columns.size < 15) continue

            try {
                val configBase64 = columns[14].trim()
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
                continue
            }
        }

        return servers.sortedByDescending { it.score }
    }
}
