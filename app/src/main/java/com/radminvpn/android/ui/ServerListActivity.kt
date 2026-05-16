package com.radminvpn.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityServerListBinding
import com.radminvpn.android.model.VpnGateServer
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.OpenVpnService
import com.radminvpn.android.vpn.VpnGateRepository
import kotlinx.coroutines.launch

class ServerListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Servers"
    }

    private lateinit var binding: ActivityServerListBinding
    private var servers: List<VpnGateServer> = emptyList()
    private var pendingServer: VpnGateServer? = null

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

        checkCurrentConnection()
        loadServers()
    }

    private fun loadServers() {
        // Show loading
        binding.layoutServers.removeAllViews()
        val loading = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = (48 * resources.displayMetrics.density).toInt()
            }
        }
        binding.layoutServers.addView(loading)

        val loadingText = TextView(this).apply {
            text = "Loading VPN Gate servers..."
            textSize = 14f
            setTextColor(Color.parseColor("#9E9EAE"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        binding.layoutServers.addView(loadingText)

        lifecycleScope.launch {
            val result = VpnGateRepository.fetchServers()
            binding.layoutServers.removeAllViews()

            result.fold(
                onSuccess = { serverList ->
                    servers = serverList
                    binding.tvServerCount.text = "${serverList.size} servers online"
                    buildServerList(serverList)
                    VpnLog.success(TAG, "Loaded ${serverList.size} servers from VPN Gate")
                },
                onFailure = { error ->
                    showError("Failed to load servers: ${error.message}")
                    VpnLog.e(TAG, "Failed to load: ${error.message}")
                }
            )
        }
    }

    private fun showError(message: String) {
        val errorView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            gravity = Gravity.CENTER
            setPadding(
                (32 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }
        binding.layoutServers.addView(errorView)

        val retryBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Retry"
            setOnClickListener { loadServers() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        binding.layoutServers.addView(retryBtn)
    }

    private fun buildServerList(servers: List<VpnGateServer>) {
        val density = resources.displayMetrics.density
        val grouped = servers.groupBy { it.countryLong }
            .toSortedMap()
            .entries
            .sortedByDescending { it.value.sumOf { s -> s.score } }

        for ((country, countryServers) in grouped) {
            val flag = countryServers.first().countryFlag

            // Country header
            val header = TextView(this).apply {
                text = "$flag  $country (${countryServers.size})"
                textSize = 14f
                setTextColor(Color.parseColor("#2979FF"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
            }
            binding.layoutServers.addView(header)

            // Show top 5 servers per country to avoid overwhelming UI
            for (server in countryServers.take(5)) {
                val card = createServerCard(server, density)
                binding.layoutServers.addView(card)
            }

            if (countryServers.size > 5) {
                val moreText = TextView(this).apply {
                    text = "  +${countryServers.size - 5} more servers"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6E6E7E"))
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
                binding.layoutServers.addView(moreText)
            }
        }
    }

    private fun createServerCard(server: VpnGateServer, density: Float): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
            radius = 12 * density
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
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt()
            )
        }

        // Speed indicator dot (green = fast, yellow = medium, red = slow)
        val speedColor = when {
            server.speedMbps > 50 -> "#4CAF50"
            server.speedMbps > 10 -> "#FFC107"
            else -> "#FF5252"
        }
        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (8 * density).toInt(), (8 * density).toInt()
            ).apply { marginEnd = (10 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(speedColor))
            }
        }
        content.addView(statusDot)

        // Server info
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = "${server.hostName}"
            textSize = 13f
            setTextColor(Color.parseColor("#E0E0E0"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        infoLayout.addView(nameView)

        val detailView = TextView(this).apply {
            text = "${String.format("%.1f", server.speedMbps)} Mbps • ${server.ping}ms • ${server.numVpnSessions} users"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9EAE"))
        }
        infoLayout.addView(detailView)

        content.addView(infoLayout)

        // Connect arrow
        val connectIcon = TextView(this).apply {
            text = "\u25B6"
            textSize = 16f
            setTextColor(Color.parseColor("#2979FF"))
            gravity = Gravity.CENTER
        }
        content.addView(connectIcon)

        card.addView(content)
        return card
    }

    private fun onServerClicked(server: VpnGateServer) {
        if (OpenVpnService.isConnected && OpenVpnService.currentServerIp == server.ip) {
            // Already connected - show share dialog
            showShareKeyDialog(server)
            return
        }

        if (OpenVpnService.isConnected) {
            disconnectFromServer()
        }

        VpnLog.i(TAG, "Connecting to ${server.hostName} (${server.ip})...")

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingServer = server
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            connectToServer(server)
        }
    }

    private fun connectToServer(server: VpnGateServer) {
        val intent = Intent(this, OpenVpnService::class.java).apply {
            action = OpenVpnService.ACTION_CONNECT
            putExtra(OpenVpnService.EXTRA_CONFIG_BASE64, server.openVpnConfigBase64)
            putExtra(OpenVpnService.EXTRA_SERVER_NAME, "${server.countryFlag} ${server.hostName}")
            putExtra(OpenVpnService.EXTRA_SERVER_IP, server.ip)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Update UI
        binding.layoutConnectionStatus.isVisible = true
        binding.tvConnectedServer.text = "${server.countryFlag} ${server.hostName} • ${String.format("%.1f", server.speedMbps)} Mbps"

        Toast.makeText(this, "Connected to ${server.hostName}", Toast.LENGTH_SHORT).show()
        VpnLog.success(TAG, "Connected to ${server.hostName}")

        // Show share key option after short delay
        binding.layoutConnectionStatus.postDelayed({
            showShareKeyButton(server)
        }, 1000)
    }

    private fun showShareKeyButton(server: VpnGateServer) {
        // Add a "Share Key" button to the connection banner
        binding.tvConnectedServer.setOnClickListener {
            showShareKeyDialog(server)
        }
        binding.tvConnectedServer.text = "${server.countryFlag} ${server.hostName} • Tap to share key"
    }

    private fun showShareKeyDialog(server: VpnGateServer) {
        // Create a shareable key = base64 of the OpenVPN config + server info
        // Another user can paste this key and connect to the same server
        val shareData = "VPNGATE|${server.ip}|${server.hostName}|${server.countryShort}|${server.openVpnConfigBase64}"
        val shareKey = android.util.Base64.encodeToString(shareData.toByteArray(), android.util.Base64.NO_WRAP)

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VPN Key", shareKey))

        Toast.makeText(this, "Connection key copied! Share it with a friend.", Toast.LENGTH_LONG).show()
        VpnLog.success(TAG, "Share key generated for ${server.hostName} (${shareKey.length} chars)")

        // Also offer Android share
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareKey)
            putExtra(Intent.EXTRA_SUBJECT, "VPN connection key - ${server.hostName}")
        }
        startActivity(Intent.createChooser(shareIntent, "Share VPN key via"))
    }

    private fun disconnectFromServer() {
        val intent = Intent(this, OpenVpnService::class.java).apply {
            action = OpenVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        binding.layoutConnectionStatus.isVisible = false
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        VpnLog.i(TAG, "Disconnected")
    }

    private fun checkCurrentConnection() {
        if (OpenVpnService.isConnected) {
            binding.layoutConnectionStatus.isVisible = true
            binding.tvConnectedServer.text = "${OpenVpnService.currentServerName} • Connected"
        }
    }
}
