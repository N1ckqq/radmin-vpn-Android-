package com.radminvpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.radminvpn.android.R
import com.radminvpn.android.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VPN сервис - создаёт TUN интерфейс и маршрутизирует
 * IP-пакеты через WebRTC DataChannel.
 */
class P2PVpnService : VpnService() {

    companion object {
        private const val TAG = "P2PVpnService"
        private const val CHANNEL_ID = "p2p_vpn_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.radminvpn.android.START_VPN"
        const val ACTION_STOP = "com.radminvpn.android.STOP_VPN"
        const val EXTRA_VIRTUAL_IP = "virtual_ip"
        const val EXTRA_PEER_VIRTUAL_IP = "peer_virtual_ip"

        var instance: P2PVpnService? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReadThread: Thread? = null
    private var isRunning = false

    // Callback для отправки пакетов через WebRTC
    var onPacketReceived: ((ByteArray) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val virtualIp = intent.getStringExtra(EXTRA_VIRTUAL_IP) ?: "10.0.0.1"
                val peerVirtualIp = intent.getStringExtra(EXTRA_PEER_VIRTUAL_IP) ?: "10.0.0.2"
                startVpn(virtualIp, peerVirtualIp)
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

    /**
     * Запустить VPN — создать TUN интерфейс
     */
    private fun startVpn(virtualIp: String, peerVirtualIp: String) {
        if (isRunning) return

        startForeground(NOTIFICATION_ID, buildNotification())

        // Создаём TUN интерфейс
        val builder = Builder()
            .setSession("P2P VPN")
            .addAddress(virtualIp, 24) // 10.0.0.x/24
            .addRoute("10.0.0.0", 24) // Маршрутизировать только виртуальную подсеть
            .setMtu(1400) // Оставляем место для WebRTC overhead
            .setBlocking(true)

        // Не перехватываем весь трафик — только виртуальную подсеть
        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        isRunning = true
        startTunReader()

        Log.i(TAG, "VPN started with IP: $virtualIp, peer: $peerVirtualIp")
    }

    /**
     * Читать пакеты из TUN интерфейса и отправлять через WebRTC
     */
    private fun startTunReader() {
        tunReadThread = Thread {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(1500)

            try {
                while (isRunning) {
                    buffer.clear()
                    val length = input.read(buffer.array())
                    if (length > 0) {
                        val packet = ByteArray(length)
                        System.arraycopy(buffer.array(), 0, packet, 0, length)
                        // Отправить пакет через WebRTC DataChannel
                        onPacketReceived?.invoke(packet)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "TUN read error: ${e.message}")
                }
            }
        }.apply {
            name = "TUN-Reader"
            isDaemon = true
            start()
        }
    }

    /**
     * Записать входящий пакет в TUN интерфейс
     * (пакет получен от удалённого пира через WebRTC)
     */
    fun writePacket(data: ByteArray) {
        try {
            val output = FileOutputStream(vpnInterface?.fileDescriptor ?: return)
            output.write(data)
        } catch (e: Exception) {
            Log.e(TAG, "TUN write error: ${e.message}")
        }
    }

    /**
     * Остановить VPN
     */
    private fun stopVpn() {
        isRunning = false
        tunReadThread?.interrupt()
        tunReadThread = null

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "VPN stopped")
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
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
