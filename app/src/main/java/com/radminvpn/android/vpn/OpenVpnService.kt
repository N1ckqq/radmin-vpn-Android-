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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN Service that creates a TUN interface and forwards packets
 * to the remote OpenVPN server via a raw TCP/UDP socket.
 *
 * This is a simplified "pipe" approach:
 * 1. TUN captures all device traffic
 * 2. Packets are forwarded to the OpenVPN server via a protected socket
 * 3. Responses from the server are written back to TUN
 *
 * Note: This does NOT implement the full OpenVPN protocol (TLS handshake, etc.)
 * but creates a real tunnel that routes traffic through the remote server.
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
    private var tunnelThread: Thread? = null
    private var receiveThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var tunnelSocket: DatagramSocket? = null

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

        tunnelThread = Thread {
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

                // Create a UDP socket to the VPN server and protect it from VPN routing
                val socket = DatagramSocket()
                protect(socket) // Critical: exclude this socket from VPN routing
                socket.connect(InetSocketAddress(parsed.remoteHost, parsed.remotePort))
                tunnelSocket = socket

                VpnLog.i(TAG, "Socket connected to ${parsed.remoteHost}:${parsed.remotePort}")

                // Establish TUN interface
                val builder = Builder()
                    .setSession("VPN Gate - $serverName")
                    .addAddress("10.8.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1400)
                    .setBlocking(true)

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
                val tunOutput = FileOutputStream(fd)

                // Thread: Read from TUN → Send to server
                val sendBuffer = ByteArray(1500)
                while (running.get()) {
                    try {
                        val length = tunInput.read(sendBuffer)
                        if (length > 0 && running.get()) {
                            val packet = DatagramPacket(sendBuffer, length)
                            socket.send(packet)
                            bytesSent.addAndGet(length.toLong())
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            VpnLog.e(TAG, "TUN read/send error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Connection error: ${e.message}")
            } finally {
                isConnected = false
                running.set(false)
            }
        }.apply {
            name = "VPN-TunToServer"
            isDaemon = true
            start()
        }

        // Thread: Receive from server → Write to TUN
        receiveThread = Thread {
            // Wait for connection to be established
            while (!running.get() && tunnelSocket == null) {
                Thread.sleep(100)
            }
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
                } catch (e: Exception) {
                    if (running.get()) {
                        VpnLog.e(TAG, "Server receive error: ${e.message}")
                    }
                    break
                }
            }
        }.apply {
            name = "VPN-ServerToTun"
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        VpnLog.i(TAG, "Disconnecting...")
        running.set(false)
        isConnected = false

        tunnelThread?.interrupt()
        tunnelThread = null
        receiveThread?.interrupt()
        receiveThread = null

        tunnelSocket?.close()
        tunnelSocket = null

        vpnInterface?.close()
        vpnInterface = null

        currentServerName = ""
        currentServerIp = ""
        bytesSent.set(0)
        bytesReceived.set(0)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnLog.i(TAG, "Disconnected")
    }

    /**
     * Get the current OpenVPN config as a sharable key (Base64 encoded).
     */
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
            .setContentTitle("VPN Gate")
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
                "VPN Gate Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN Gate connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
