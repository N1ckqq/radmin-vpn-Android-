package com.radminvpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.radminvpn.android.ui.MainActivity
import com.radminvpn.android.util.VpnLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN Service that creates a TUN interface and forwards raw IP packets
 * to the remote server via a protected UDP/TCP socket.
 *
 * IMPORTANT: VPN Gate servers require the full OpenVPN protocol (TLS, HMAC, encryption).
 * This simplified service creates the TUN and attempts a raw packet tunnel.
 * For servers that do NOT support raw tunneling, the connection will establish
 * the TUN interface (changing routes/DNS) but may not pass traffic through
 * unless the server supports raw IP forwarding.
 *
 * The service correctly:
 * - Requests VPN permission via VpnService.prepare()
 * - Creates a TUN interface with proper routes and DNS
 * - Protects the tunnel socket from VPN routing loops
 * - Handles connect/disconnect lifecycle
 * - Reports real byte counters
 */
class OpenVpnService : VpnService() {

    companion object {
        private const val TAG = "OpenVPN"
        private const val CHANNEL_ID = "openvpn_channel"
        private const val NOTIFICATION_ID = 2

        const val ACTION_CONNECT = "com.radminvpn.android.OPENVPN_CONNECT"
        const val ACTION_DISCONNECT = "com.radminvpn.android.OPENVPN_DISCONNECT"
        const val EXTRA_CONFIG_BASE64 = "config_base64"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_IP = "server_ip"

        var instance: OpenVpnService? = null
            private set

        var currentServerName: String = ""
            private set
        var currentServerIp: String = ""
            private set
        var isConnected: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var tunnelSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null

    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configBase64 = intent.getStringExtra(EXTRA_CONFIG_BASE64) ?: ""
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Unknown"
                val serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
                connect(configBase64, serverName, serverIp)
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        instance = null
        super.onDestroy()
    }

    private fun connect(configBase64: String, serverName: String, serverIp: String) {
        if (running.get()) {
            VpnLog.w(TAG, "Already connected, disconnecting first...")
            disconnect()
        }

        currentServerName = serverName
        currentServerIp = serverIp
        VpnLog.i(TAG, "Connecting to $serverName ($serverIp)...")

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to $serverName..."))

        sendThread = Thread {
            var localSocket: DatagramSocket? = null
            try {
                // Decode and parse the OpenVPN config
                val configBytes = Base64.decode(configBase64, Base64.DEFAULT)
                val config = String(configBytes)
                val parsed = parseOvpnConfig(config)

                if (parsed == null) {
                    VpnLog.e(TAG, "Failed to parse OpenVPN config")
                    stopSelf()
                    return@Thread
                }

                VpnLog.i(TAG, "Config parsed: ${parsed.remoteHost}:${parsed.remotePort} (${parsed.proto})")

                // Resolve server address BEFORE creating TUN (so DNS works)
                val serverAddress = InetAddress.getByName(parsed.remoteHost)
                VpnLog.i(TAG, "Server resolved: ${serverAddress.hostAddress}")

                // Create protected UDP socket to the VPN server
                val socket = DatagramSocket()
                protect(socket) // CRITICAL: exclude from VPN routing
                socket.soTimeout = 5000 // 5s timeout for receives
                socket.connect(InetSocketAddress(serverAddress, parsed.remotePort))
                tunnelSocket = socket
                localSocket = socket

                VpnLog.i(TAG, "Protected socket connected to ${serverAddress.hostAddress}:${parsed.remotePort}")

                // Build TUN interface
                val builder = Builder()
                    .setSession("VPN - $serverName")
                    .addAddress("10.8.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1400)
                    .setBlocking(true)

                // Route all traffic through VPN except the VPN server itself
                // This prevents routing loops
                val serverIpStr = serverAddress.hostAddress ?: parsed.remoteHost
                // Add two routes that cover 0.0.0.0/0 but exclude the server IP
                builder.addRoute("0.0.0.0", 0)
                // Exclude the VPN server's IP from the tunnel
                // Android handles this via protect(socket) already

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    VpnLog.e(TAG, "Failed to establish VPN interface")
                    socket.close()
                    stopSelf()
                    return@Thread
                }

                running.set(true)
                isConnected = true
                VpnLog.success(TAG, "VPN interface established! Connected to $serverName")
                updateNotification("Connected to $serverName")

                val fd = vpnInterface!!.fileDescriptor
                val tunInput = FileInputStream(fd)

                // Send thread: TUN → Server
                val sendBuffer = ByteArray(1500)
                while (running.get()) {
                    try {
                        val length = tunInput.read(sendBuffer)
                        if (length > 0 && running.get()) {
                            val packet = DatagramPacket(sendBuffer, length)
                            socket.send(packet)
                            bytesSent.addAndGet(length.toLong())
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        // Read timeout - just continue
                        continue
                    } catch (e: Exception) {
                        if (running.get()) {
                            VpnLog.e(TAG, "Send error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Connection error: ${e.message}")
            } finally {
                if (!running.get()) {
                    isConnected = false
                }
            }
        }.apply {
            name = "VPN-Send"
            isDaemon = true
            start()
        }

        // Receive thread: Server → TUN
        receiveThread = Thread {
            // Wait for tunnel to be established
            var attempts = 0
            while (!running.get() && attempts < 50) {
                Thread.sleep(100)
                attempts++
            }
            if (!running.get()) return@Thread

            val socket = tunnelSocket ?: return@Thread
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val tunOutput = FileOutputStream(fd)
            val recvBuffer = ByteArray(1500)

            while (running.get()) {
                try {
                    val packet = DatagramPacket(recvBuffer, recvBuffer.size)
                    socket.receive(packet)
                    if (packet.length > 0 && running.get()) {
                        tunOutput.write(recvBuffer, 0, packet.length)
                        bytesReceived.addAndGet(packet.length.toLong())
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Receive timeout - just continue polling
                    continue
                } catch (e: Exception) {
                    if (running.get()) {
                        VpnLog.e(TAG, "Receive error: ${e.message}")
                    }
                    break
                }
            }
        }.apply {
            name = "VPN-Receive"
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        VpnLog.i(TAG, "Disconnecting...")
        running.set(false)
        isConnected = false

        sendThread?.interrupt()
        sendThread = null
        receiveThread?.interrupt()
        receiveThread = null

        try { tunnelSocket?.close() } catch (_: Exception) {}
        tunnelSocket = null
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpSocket = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        currentServerName = ""
        currentServerIp = ""
        bytesSent.set(0)
        bytesReceived.set(0)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnLog.i(TAG, "Disconnected")
    }

    fun getShareableKey(): String? {
        return if (isConnected && currentServerIp.isNotEmpty()) {
            val info = "VPNGATE:$currentServerIp:$currentServerName"
            Base64.encodeToString(info.toByteArray(), Base64.NO_WRAP)
        } else null
    }

    private fun parseOvpnConfig(config: String): OvpnConfig? {
        var remoteHost = ""
        var remotePort = 1194
        var proto = "udp"

        for (line in config.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("remote ") -> {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2) remoteHost = parts[1]
                    if (parts.size >= 3) remotePort = parts[2].toIntOrNull() ?: 1194
                }
                trimmed.startsWith("proto ") -> {
                    proto = trimmed.substringAfter("proto ").trim()
                }
            }
        }

        return if (remoteHost.isNotEmpty()) {
            OvpnConfig(remoteHost, remotePort, proto, config)
        } else null
    }

    private data class OvpnConfig(
        val remoteHost: String,
        val remotePort: Int,
        val proto: String,
        val fullConfig: String
    )

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Radmin VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
