package com.radminvpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.radminvpn.android.ui.MainActivity
import com.radminvpn.android.util.VpnLog
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenVPN Service that uses the ics-openvpn library (de.blinkt.openvpn)
 * to establish a real OpenVPN connection with full protocol support
 * (TLS, encryption, authentication).
 *
 * This properly:
 * - Parses .ovpn config from VPN Gate (Base64)
 * - Creates a VpnProfile using ConfigParser
 * - Launches the real OpenVPN3 engine via VPNLaunchHelper
 * - Reports real byte counters from the connection
 */
class OpenVpnService : VpnService(), VpnStatus.StateListener, VpnStatus.ByteCountListener {

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

    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
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
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        instance = null
        super.onDestroy()
    }

    private fun connect(configBase64: String, serverName: String, serverIp: String) {
        currentServerName = serverName
        currentServerIp = serverIp
        VpnLog.i(TAG, "Connecting to $serverName ($serverIp) using OpenVPN library...")

        try {
            // Decode the Base64 .ovpn config
            val configBytes = Base64.decode(configBase64, Base64.DEFAULT)
            val configString = String(configBytes)

            // Parse the OpenVPN config using ics-openvpn's ConfigParser
            val configParser = ConfigParser()
            configParser.parseConfig(StringReader(configString))

            // Convert parsed config to a VpnProfile
            val profile = configParser.convertProfile()
            profile.mName = serverName

            // Save the profile
            ProfileManager.getInstance(this).addProfile(profile)

            // Launch the VPN connection using the real OpenVPN engine
            VPNLaunchHelper.startOpenVpn(profile, this)

            VpnLog.success(TAG, "OpenVPN connection initiated for $serverName")
        } catch (e: Exception) {
            VpnLog.e(TAG, "Failed to start OpenVPN: ${e.message}")
            isConnected = false
        }
    }

    fun disconnect() {
        VpnLog.i(TAG, "Disconnecting...")

        // Send disconnect to the OpenVPN management interface
        val profile = ProfileManager.getInstance(this).getProfileByName(currentServerName)
        if (profile != null) {
            // The OpenVPN core service handles disconnect
            val mgmt = de.blinkt.openvpn.core.OpenVPNManagement.getInstance()
            mgmt?.stopVPN(false)
        }

        isConnected = false
        currentServerName = ""
        currentServerIp = ""
        bytesSent.set(0)
        bytesReceived.set(0)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnLog.i(TAG, "Disconnected")
    }

    // ===== VpnStatus.StateListener =====

    override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: VpnStatus.ConnectionStatus?, Intent: Intent?) {
        when (level) {
            VpnStatus.ConnectionStatus.LEVEL_CONNECTED -> {
                isConnected = true
                VpnLog.success(TAG, "CONNECTED to $currentServerName")
            }
            VpnStatus.ConnectionStatus.LEVEL_NOTCONNECTED -> {
                isConnected = false
                VpnLog.i(TAG, "Disconnected")
            }
            VpnStatus.ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> {
                VpnLog.i(TAG, "Server replied, authenticating...")
            }
            VpnStatus.ConnectionStatus.LEVEL_AUTH_FAILED -> {
                isConnected = false
                VpnLog.e(TAG, "Authentication failed")
            }
            else -> {
                VpnLog.i(TAG, "State: $state - $logmessage")
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    // ===== VpnStatus.ByteCountListener =====

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        bytesReceived.set(inBytes)
        bytesSent.set(outBytes)
    }

    // ===== Notification =====

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

    fun getShareableKey(): String? {
        return if (isConnected && currentServerIp.isNotEmpty()) {
            val info = "VPNGATE:$currentServerIp:$currentServerName"
            Base64.encodeToString(info.toByteArray(), Base64.NO_WRAP)
        } else null
    }
}
