package com.radminvpn.android.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.databinding.ActivityStatsBinding
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.P2PVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Stats"
    }

    private lateinit var binding: ActivityStatsBinding
    private var refreshJob: Job? = null
    private var startTime: Long = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        VpnLog.i(TAG, "Stats activity started")
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                updateStats()
                delay(1000)
            }
        }
    }

    private suspend fun updateStats() {
        withContext(Dispatchers.Main) {
            val service = P2PVpnService.instance

            if (service != null) {
                // Connection state
                binding.tvConnectionState.text = "VPN Active"
                setConnectionDotColor("#4CAF50")

                // Duration
                val durationMs = System.currentTimeMillis() - startTime
                binding.tvDuration.text = "Duration: ${formatDuration(durationMs)}"

                // Packets
                binding.tvPacketsSent.text = formatNumber(service.packetsSent.get())
                binding.tvPacketsReceived.text = formatNumber(service.packetsReceived.get())

                // Bytes
                binding.tvBytesSent.text = formatBytes(service.bytesSent.get())
                binding.tvBytesReceived.text = formatBytes(service.bytesReceived.get())

                // Peers
                updatePeerList()
            } else {
                binding.tvConnectionState.text = "VPN Inactive"
                setConnectionDotColor("#9E9E9E")
                binding.tvDuration.text = "Duration: --:--:--"
                binding.tvPacketsSent.text = "0"
                binding.tvPacketsReceived.text = "0"
                binding.tvBytesSent.text = "0 B"
                binding.tvBytesReceived.text = "0 B"
                binding.tvNoPeers.visibility = View.VISIBLE
                binding.layoutPeers.removeAllViews()
            }
        }
    }

    private fun updatePeerList() {
        binding.layoutPeers.removeAllViews()
        val service = P2PVpnService.instance

        if (service == null) {
            binding.tvNoPeers.visibility = View.VISIBLE
            return
        }

        // Show at least a connected indicator if VPN is running
        val isConnected = service.packetsSent.get() > 0 || service.packetsReceived.get() > 0

        if (isConnected) {
            binding.tvNoPeers.visibility = View.GONE
            addPeerView("manual-peer", "10.0.0.x", true)
        } else {
            binding.tvNoPeers.visibility = View.VISIBLE
        }
    }

    private fun addPeerView(peerId: String, ip: String, connected: Boolean) {
        val peerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10, 10).apply {
                marginEnd = 12
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
            }
        }
        peerLayout.addView(dot)

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tvPeerId = TextView(this).apply {
            text = peerId
            textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoLayout.addView(tvPeerId)

        val tvIp = TextView(this).apply {
            text = "IP: $ip | Status: ${if (connected) "Connected" else "Disconnected"}"
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
        }
        infoLayout.addView(tvIp)

        peerLayout.addView(infoLayout)
        binding.layoutPeers.addView(peerLayout)
    }

    private fun setConnectionDotColor(color: String) {
        val dot = binding.viewConnectionDot.background
        if (dot is GradientDrawable) {
            dot.setColor(Color.parseColor(color))
        } else {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
            binding.viewConnectionDot.background = shape
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatNumber(num: Long): String {
        return when {
            num < 1000 -> num.toString()
            num < 1_000_000 -> String.format("%.1fK", num / 1000.0)
            else -> String.format("%.2fM", num / 1_000_000.0)
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }
}
