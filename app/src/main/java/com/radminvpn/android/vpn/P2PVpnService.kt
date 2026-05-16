package com.radminvpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.radminvpn.android.R
import com.radminvpn.android.ui.MainActivity
import com.radminvpn.android.util.VpnLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN Service — creates TUN interface and routes IP packets through WebRTC.
 */
class P2PVpnService : VpnService() {

    companion object {
        private const val TAG = "VpnService"
        private const val CHANNEL_ID = "p2p_vpn_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.radminvpn.android.START_VPN"
        const val ACTION_STOP = "com.radminvpn.android.STOP_VPN"
        const val EXTRA_VIRTUAL_IP = "virtual_ip"

        var instance: P2PVpnService? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReadThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Stats
    val packetsSent = AtomicLong(0)
    val packetsReceived = AtomicLong(0)
    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    // Callback for sending packets through WebRTC
    var onPacketReceived: ((ByteArray) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        VpnLog.i(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val virtualIp = intent.getStringExtra(EXTRA_VIRTUAL_IP) ?: "10.0.0.1"
                startVpn(virtualIp)
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        instance = null
        super.onDestroy()
    }

    private fun startVpn(virtualIp: String) {
        if (isRunning.get()) {
            VpnLog.w(TAG, "VPN already running")
            return
        }

        VpnLog.i(TAG, "Starting VPN with IP: $virtualIp")
        startForeground(NOTIFICATION_ID, buildNotification(virtualIp))

        try {
            val builder = Builder()
                .setSession("P2P VPN")
                .addAddress(virtualIp, 24)
                .addRoute("10.0.0.0", 24)
                .setMtu(1400)
                .setBlocking(true)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                VpnLog.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            isRunning.set(true)
            startTunReader()

            VpnLog.success(TAG, "VPN interface established! IP: $virtualIp/24")
            VpnLog.i(TAG, "Routing 10.0.0.0/24 through tunnel")
        } catch (e: Exception) {
            VpnLog.e(TAG, "VPN start error: ${e.message}")
            stopSelf()
        }
    }

    private fun startTunReader() {
        tunReadThread = Thread {
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val input = FileInputStream(fd)
            val buffer = ByteBuffer.allocate(1500)

            VpnLog.d(TAG, "TUN reader thread started")

            try {
                while (isRunning.get()) {
                    buffer.clear()
                    val length = input.read(buffer.array())
                    if (length > 0) {
                        val packet = ByteArray(length)
                        System.arraycopy(buffer.array(), 0, packet, 0, length)
                        packetsSent.incrementAndGet()
                        bytesSent.addAndGet(length.toLong())
                        onPacketReceived?.invoke(packet)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    VpnLog.e(TAG, "TUN read error: ${e.message}")
                }
            }

            VpnLog.d(TAG, "TUN reader thread stopped")
        }.apply {
            name = "TUN-Reader"
            isDaemon = true
            start()
        }
    }

    /**
     * Write incoming packet to TUN (received from remote peer via WebRTC)
     */
    fun writePacket(data: ByteArray) {
        try {
            val fd = vpnInterface?.fileDescriptor ?: return
            val output = FileOutputStream(fd)
            output.write(data)
            packetsReceived.incrementAndGet()
            bytesReceived.addAndGet(data.size.toLong())
        } catch (e: Exception) {
            VpnLog.e(TAG, "TUN write error: ${e.message}")
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) return

        VpnLog.i(TAG, "Stopping VPN...")
        VpnLog.i(TAG, "Stats: sent=${packetsSent.get()} pkts (${bytesSent.get()} B), " +
                "recv=${packetsReceived.get()} pkts (${bytesReceived.get()} B)")

        tunReadThread?.interrupt()
        tunReadThread = null

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        VpnLog.i(TAG, "VPN stopped")
    }

    private fun buildNotification(ip: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P VPN Active")
            .setContentText("Virtual IP: $ip")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2P VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
