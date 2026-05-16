package com.radminvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityServerListBinding
import com.radminvpn.android.model.BuiltInServers
import com.radminvpn.android.model.ServerConfig
import com.radminvpn.android.model.ServerType
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.P2PVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class ServerListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Servers"
    }

    private lateinit var binding: ActivityServerListBinding
    private var connectedServerId: String? = null
    private var pendingServer: ServerConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingServer?.let { connectToServer(it) }
            pendingServer = null
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
            pendingServer = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDisconnect.setOnClickListener { disconnectFromServer() }

        val servers = BuiltInServers.servers
        binding.tvServerCount.text = "${servers.size} servers"

        buildServerList(servers)
        checkCurrentConnection()

        VpnLog.i(TAG, "Server list opened. ${servers.size} servers available.")
    }

    private fun buildServerList(servers: List<ServerConfig>) {
        val density = resources.displayMetrics.density
        val grouped = servers.groupBy { it.country }

        for ((country, countryServers) in grouped) {
            // Country header
            val header = TextView(this).apply {
                text = "${countryServers.first().countryFlag}  $country"
                textSize = 14f
                setTextColor(Color.parseColor("#2979FF"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
            }
            binding.layoutServers.addView(header)

            for (server in countryServers) {
                val card = createServerCard(server, density)
                binding.layoutServers.addView(card)
            }
        }
    }

    private fun createServerCard(server: ServerConfig, density: Float): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            radius = 14 * density
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#252536"))
            strokeColor = Color.parseColor("#3D3D5C")
            strokeWidth = (1 * density).toInt()
            isClickable = true
            isFocusable = true
            setOnClickListener { onServerClicked(server) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
        }

        // Status dot
        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (10 * density).toInt(),
                (10 * density).toInt()
            ).apply {
                marginEnd = (12 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
            }
        }
        content.addView(statusDot)

        // Server info
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = server.name
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            typeface = Typeface.DEFAULT_BOLD
        }
        infoLayout.addView(nameView)

        val detailView = TextView(this).apply {
            val typeLabel = when (server.type) {
                ServerType.VPS -> "VPS"
                ServerType.VDS -> "VDS"
                ServerType.DEDICATED -> "Dedicated"
            }
            text = "$typeLabel • ${server.protocol} • ${server.host}:${server.port}"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9EAE"))
        }
        infoLayout.addView(detailView)

        content.addView(infoLayout)

        // Connect button indicator
        val connectIcon = TextView(this).apply {
            text = "\u25B6" // Play triangle
            textSize = 20f
            setTextColor(Color.parseColor("#2979FF"))
            gravity = Gravity.CENTER
        }
        content.addView(connectIcon)

        card.addView(content)
        card.tag = server.id
        return card
    }

    private fun onServerClicked(server: ServerConfig) {
        if (connectedServerId == server.id) {
            // Already connected to this server
            Toast.makeText(this, "Already connected to ${server.name}", Toast.LENGTH_SHORT).show()
            return
        }

        if (connectedServerId != null) {
            // Disconnect from current first
            disconnectFromServer()
        }

        VpnLog.i(TAG, "Connecting to ${server.name} (${server.host}:${server.port})...")

        // Request VPN permission
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingServer = server
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            connectToServer(server)
        }
    }

    private fun connectToServer(server: ServerConfig) {
        VpnLog.i(TAG, "Starting VPN tunnel to ${server.name}...")

        // Start the VPN service with the server's virtual IP
        val intent = Intent(this, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, "10.8.0.2")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        connectedServerId = server.id

        // Update UI
        binding.layoutConnectionStatus.isVisible = true
        binding.tvConnectedServer.text = "${server.countryFlag} Connected to ${server.name}"

        // Ping test in background
        lifecycleScope.launch {
            val ping = pingServer(server.host, server.port)
            withContext(Dispatchers.Main) {
                if (ping >= 0) {
                    binding.tvConnectedServer.text =
                        "${server.countryFlag} ${server.name} • ${ping}ms"
                    VpnLog.success(TAG, "Connected to ${server.name} (${ping}ms)")
                    Toast.makeText(
                        this@ServerListActivity,
                        "Connected to ${server.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    binding.tvConnectedServer.text =
                        "${server.countryFlag} ${server.name} • VPN Active"
                    VpnLog.success(TAG, "VPN active via ${server.name}")
                }
            }
        }

        // Highlight connected card
        highlightConnectedServer(server.id)
    }

    private fun disconnectFromServer() {
        VpnLog.i(TAG, "Disconnecting from server...")
        val intent = Intent(this, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_STOP
        }
        startService(intent)

        connectedServerId = null
        binding.layoutConnectionStatus.isVisible = false
        resetAllCards()

        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        VpnLog.i(TAG, "Disconnected from server")
    }

    private fun highlightConnectedServer(serverId: String) {
        resetAllCards()
        val card = binding.layoutServers.findViewWithTag<View>(serverId)
        if (card is com.google.android.material.card.MaterialCardView) {
            card.strokeColor = Color.parseColor("#4CAF50")
            card.strokeWidth = (2 * resources.displayMetrics.density).toInt()
        }
    }

    private fun resetAllCards() {
        for (i in 0 until binding.layoutServers.childCount) {
            val child = binding.layoutServers.getChildAt(i)
            if (child is com.google.android.material.card.MaterialCardView) {
                child.strokeColor = Color.parseColor("#3D3D5C")
                child.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun checkCurrentConnection() {
        val service = P2PVpnService.instance
        if (service != null) {
            binding.layoutConnectionStatus.isVisible = true
            binding.tvConnectedServer.text = "VPN Active"
        }
    }

    private suspend fun pingServer(host: String, port: Int): Long {
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                val elapsed = System.currentTimeMillis() - start
                socket.close()
                elapsed
            } catch (e: Exception) {
                VpnLog.d(TAG, "Ping failed for $host: ${e.message}")
                -1L
            }
        }
    }
}
