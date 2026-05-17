package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityMainBinding
import com.radminvpn.android.model.VpnGateServer
import com.radminvpn.android.vpn.OpenVpnService
import com.radminvpn.android.vpn.VpnGateRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Connection state
    private var isConnected = false
    private var isConnecting = false
    private var connectionStartTime = 0L
    private val timerHandler = Handler(Looper.getMainLooper())

    // Animation handlers
    private var orbPulseAnimator: AnimatorSet? = null
    private var connectingAnimator: AnimatorSet? = null

    // Traffic stats simulation
    private var totalUpload = 0L
    private var totalDownload = 0L
    private val statsHandler = Handler(Looper.getMainLooper())

    // Current server info
    private var currentServerName = ""
    private var currentServerIp = ""
    private var currentServerCountry = ""
    private var currentServerSpeed = 0L
    private var currentServerPing = 0

    // Pending server to connect after VPN permission granted
    private var pendingServer: VpnGateServer? = null

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingServer?.let { startVpnConnection(it) }
        } else {
            isConnecting = false
            updateUiState()
            stopAllAnimations()
            startIdleOrbAnimation()
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingServer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAnimations()
        setupClickListeners()
        setupBottomNavigation()
        loadRecentServer()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        if (isConnected) {
            startOrbPulseAnimation()
            startTimer()
            startStatsUpdater()
        } else {
            startIdleOrbAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAllAnimations()
    }

    // ===== ANIMATIONS =====

    private fun setupAnimations() {
        startIdleOrbAnimation()
        animateEntrance()
    }

    private fun animateEntrance() {
        // Fade in the main content with staggered delay
        binding.viewConnectionOrb.alpha = 0f
        binding.viewConnectionOrb.scaleX = 0.5f
        binding.viewConnectionOrb.scaleY = 0.5f

        binding.viewConnectionOrb.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        binding.tvConnectionStatus.alpha = 0f
        binding.tvConnectionStatus.translationY = 30f
        binding.tvConnectionStatus.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(200)
            .start()

        binding.btnQuickConnect.alpha = 0f
        binding.btnQuickConnect.translationY = 40f
        binding.btnQuickConnect.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(400)
            .start()
    }

    private fun startIdleOrbAnimation() {
        stopAllAnimations()

        val pulseOuter = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseOuterY = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseMiddle = ObjectAnimator.ofFloat(binding.viewOrbPulseMiddle, "scaleX", 1f, 1.1f, 1f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseMiddleY = ObjectAnimator.ofFloat(binding.viewOrbPulseMiddle, "scaleY", 1f, 1.1f, 1f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alphaOuter = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "alpha", 0.2f, 0.4f, 0.2f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
        }

        orbPulseAnimator = AnimatorSet().apply {
            playTogether(pulseOuter, pulseOuterY, pulseMiddle, pulseMiddleY, alphaOuter)
            start()
        }
    }

    private fun startOrbPulseAnimation() {
        stopAllAnimations()

        val pulseOuter = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseOuterY = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseMiddle = ObjectAnimator.ofFloat(binding.viewOrbPulseMiddle, "scaleX", 1f, 1.2f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseMiddleY = ObjectAnimator.ofFloat(binding.viewOrbPulseMiddle, "scaleY", 1f, 1.2f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alphaOuter = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "alpha", 0.2f, 0.6f, 0.2f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
        }
        val alphaMiddle = ObjectAnimator.ofFloat(binding.viewOrbPulseMiddle, "alpha", 0.4f, 0.8f, 0.4f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
        }

        orbPulseAnimator = AnimatorSet().apply {
            playTogether(pulseOuter, pulseOuterY, pulseMiddle, pulseMiddleY, alphaOuter, alphaMiddle)
            start()
        }
    }

    private fun startConnectingAnimation() {
        stopAllAnimations()

        val rotation = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "rotation", 0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleX = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "scaleX", 1f, 0.9f, 1.1f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "scaleY", 1f, 0.9f, 1.1f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
        }
        val alphaOuter = ObjectAnimator.ofFloat(binding.viewOrbPulseOuter, "alpha", 0.1f, 0.5f, 0.1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
        }

        connectingAnimator = AnimatorSet().apply {
            playTogether(rotation, scaleX, scaleY, alphaOuter)
            start()
        }
    }

    private fun animateConnectionSuccess() {
        // Scale bounce effect on orb
        val scaleX = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "scaleX", 1f, 1.3f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "scaleY", 1f, 1.3f, 1f)
        val alpha = ObjectAnimator.ofFloat(binding.viewConnectionOrb, "alpha", 1f, 0.7f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 500
            interpolator = OvershootInterpolator()
            start()
        }

        // Reset rotation from connecting animation
        binding.viewConnectionOrb.rotation = 0f
    }

    private fun animateButtonPress(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f)
            )
            duration = 100
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f)
            )
            duration = 100
            interpolator = OvershootInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    private fun stopAllAnimations() {
        orbPulseAnimator?.cancel()
        orbPulseAnimator = null
        connectingAnimator?.cancel()
        connectingAnimator = null
        timerHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
    }

    // ===== CLICK LISTENERS =====

    private fun setupClickListeners() {
        // Quick Connect - picks best server and connects
        binding.btnQuickConnect.setOnClickListener {
            animateButtonPress(it)
            if (!isConnected && !isConnecting) {
                quickConnect()
            }
        }

        // Connection Orb tap
        binding.viewConnectionOrb.setOnClickListener {
            animateButtonPress(it)
            if (isConnected) {
                showDisconnectConfirmation()
            } else if (!isConnecting) {
                quickConnect()
            }
        }

        // Generate Key
        binding.btnGenerateKey.setOnClickListener {
            animateButtonPress(it)
            if (isConnected) {
                navigateToConnected()
            } else {
                Toast.makeText(this, getString(R.string.connect_first), Toast.LENGTH_SHORT).show()
            }
        }

        // Join With Key
        binding.btnJoinWithKey.setOnClickListener {
            animateButtonPress(it)
            showJoinWithKeyDialog()
        }

        // Disconnect
        binding.btnDisconnect.setOnClickListener {
            animateButtonPress(it)
            showDisconnectConfirmation()
        }

        // Recent server card
        binding.cardRecentServer.setOnClickListener {
            animateButtonPress(it)
            navigateToServerList()
        }

        // Top settings button
        binding.btnTopSettings.setOnClickListener {
            animateButtonPress(it)
            navigateToSettings()
        }
    }

    // ===== BOTTOM NAVIGATION =====

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener {
            animateButtonPress(it)
            setNavActive(0)
        }

        binding.navServers.setOnClickListener {
            animateButtonPress(it)
            setNavActive(1)
            navigateToServerList()
        }

        binding.navChat.setOnClickListener {
            animateButtonPress(it)
            setNavActive(2)
            navigateToChat()
        }

        binding.navSettings.setOnClickListener {
            animateButtonPress(it)
            setNavActive(3)
            navigateToSettings()
        }
    }

    private fun setNavActive(index: Int) {
        val icons = listOf(binding.ivNavHome, binding.ivNavServers, binding.ivNavChat, binding.ivNavSettings)
        val labels = listOf(binding.tvNavHome, binding.tvNavServers, binding.tvNavChat, binding.tvNavSettings)

        val activeColor = getColor(R.color.accent_blue)
        val inactiveColor = getColor(R.color.text_secondary)

        for (i in icons.indices) {
            if (i == index) {
                icons[i].setColorFilter(activeColor)
                labels[i].setTextColor(activeColor)
                // Bounce animation on active tab
                icons[i].animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction {
                    icons[i].animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            } else {
                icons[i].setColorFilter(inactiveColor)
                labels[i].setTextColor(inactiveColor)
            }
        }
    }

    // ===== CONNECTION LOGIC =====

    private fun quickConnect() {
        isConnecting = true
        updateUiState()
        startConnectingAnimation()

        binding.tvConnectionStatus.text = getString(R.string.status_connecting)
        binding.tvConnectionSubtitle.text = getString(R.string.finding_best_server)

        lifecycleScope.launch {
            try {
                val result = VpnGateRepository.getBestServer()
                result.onSuccess { server ->
                    currentServerName = server.hostName
                    currentServerIp = server.ip
                    currentServerCountry = server.countryLong
                    currentServerSpeed = server.speed
                    currentServerPing = server.ping

                    runOnUiThread {
                        requestVpnPermissionAndConnect(server)
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        onConnectionFailed(error.message ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    onConnectionFailed(e.message ?: "Connection failed")
                }
            }
        }
    }

    private fun requestVpnPermissionAndConnect(server: VpnGateServer) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingServer = server
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Permission already granted
            startVpnConnection(server)
        }
    }

    private fun startVpnConnection(server: VpnGateServer) {
        val intent = Intent(this, OpenVpnService::class.java).apply {
            action = OpenVpnService.ACTION_CONNECT
            putExtra(OpenVpnService.EXTRA_CONFIG_BASE64, server.openVpnConfigBase64)
            putExtra(OpenVpnService.EXTRA_SERVER_NAME, server.hostName)
            putExtra(OpenVpnService.EXTRA_SERVER_IP, server.ip)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Wait briefly for the service to establish TUN then confirm success
        Handler(Looper.getMainLooper()).postDelayed({
            if (OpenVpnService.isConnected) {
                onConnectionSuccess()
            } else {
                // Give more time - check again
                Handler(Looper.getMainLooper()).postDelayed({
                    if (OpenVpnService.isConnected) {
                        onConnectionSuccess()
                    } else {
                        onConnectionFailed("Failed to establish VPN tunnel")
                    }
                }, 3000)
            }
        }, 2000)
    }

    private fun onConnectionSuccess() {
        isConnecting = false
        isConnected = true
        connectionStartTime = System.currentTimeMillis()

        animateConnectionSuccess()
        startOrbPulseAnimation()
        startTimer()
        startStatsUpdater()
        updateUiState()
        saveRecentServer()

        // Animate connected card appearance
        binding.cardNetworkInfo.alpha = 0f
        binding.cardNetworkInfo.translationY = 30f
        binding.cardNetworkInfo.isVisible = true
        binding.cardNetworkInfo.animate().alpha(1f).translationY(0f).setDuration(400).start()

        binding.cardTrafficStats.alpha = 0f
        binding.cardTrafficStats.translationY = 30f
        binding.cardTrafficStats.isVisible = true
        binding.cardTrafficStats.animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(100).start()
    }

    private fun onConnectionFailed(error: String) {
        isConnecting = false
        isConnected = false
        stopAllAnimations()
        startIdleOrbAnimation()
        updateUiState()

        binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
        binding.tvConnectionSubtitle.text = getString(R.string.status_error, error)

        Toast.makeText(this, getString(R.string.status_error, error), Toast.LENGTH_LONG).show()
    }

    private fun disconnect() {
        isConnected = false
        isConnecting = false
        connectionStartTime = 0L
        totalUpload = 0L
        totalDownload = 0L

        // Stop VPN service
        val intent = Intent(this, OpenVpnService::class.java).apply {
            action = OpenVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        stopAllAnimations()
        startIdleOrbAnimation()
        updateUiState()

        // Animate cards disappearing
        binding.cardNetworkInfo.animate().alpha(0f).translationY(30f).setDuration(300).withEndAction {
            binding.cardNetworkInfo.isVisible = false
        }.start()
        binding.cardTrafficStats.animate().alpha(0f).translationY(30f).setDuration(300).withEndAction {
            binding.cardTrafficStats.isVisible = false
        }.start()
    }

    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(this, R.style.Theme_RadminVPN_Dialog)
            .setTitle(R.string.disconnect_confirm_title)
            .setMessage(R.string.disconnect_confirm_message)
            .setPositiveButton(R.string.disconnect) { _, _ -> disconnect() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== JOIN WITH KEY =====

    private fun showJoinWithKeyDialog() {
        val dialogView = layoutInflater.inflate(R.layout.activity_main, null)
        // Simple dialog with EditText
        val editText = EditText(this).apply {
            hint = getString(R.string.paste_key_hint)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            maxLines = 3
        }

        AlertDialog.Builder(this, R.style.Theme_RadminVPN_Dialog)
            .setTitle(R.string.join_with_key)
            .setView(editText)
            .setPositiveButton(R.string.connect) { _, _ ->
                val key = editText.text.toString().trim()
                if (key.isNotEmpty()) {
                    joinWithKey(key)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun joinWithKey(encodedKey: String) {
        try {
            val decoded = String(Base64.decode(encodedKey, Base64.DEFAULT))
            // Parse the decoded key for server info
            val parts = decoded.split("|")
            if (parts.size >= 3) {
                currentServerName = parts[0]
                currentServerIp = parts[1]
                currentServerCountry = parts[2]
                currentServerSpeed = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                currentServerPing = parts.getOrNull(4)?.toIntOrNull() ?: 0

                isConnecting = true
                updateUiState()
                startConnectingAnimation()
                binding.tvConnectionStatus.text = getString(R.string.status_connecting)

                // Try to get server config and connect properly
                lifecycleScope.launch {
                    try {
                        val result = VpnGateRepository.fetchServers()
                        result.onSuccess { servers ->
                            val server = servers.find { it.ip == currentServerIp }
                            if (server != null) {
                                runOnUiThread { requestVpnPermissionAndConnect(server) }
                            } else {
                                runOnUiThread { onConnectionFailed("Server not found") }
                            }
                        }.onFailure {
                            runOnUiThread { onConnectionFailed(it.message ?: "Failed") }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { onConnectionFailed(e.message ?: "Failed") }
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
        }
    }

    // ===== TIMER =====

    private fun startTimer() {
        timerHandler.removeCallbacksAndMessages(null)
        val timerRunnable = object : Runnable {
            override fun run() {
                if (isConnected && connectionStartTime > 0) {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    val hours = (elapsed / 3600000).toInt()
                    val minutes = ((elapsed % 3600000) / 60000).toInt()
                    val seconds = ((elapsed % 60000) / 1000).toInt()
                    val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    binding.tvConnectionTimer.text = timeStr
                }
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable)
    }

    // ===== STATS UPDATER =====

    private fun startStatsUpdater() {
        statsHandler.removeCallbacksAndMessages(null)
        val statsRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    val service = OpenVpnService.instance
                    if (service != null) {
                        totalUpload = service.bytesSent.get()
                        totalDownload = service.bytesReceived.get()
                    }

                    binding.tvUploadSpeed.text = formatSpeed(totalUpload)
                    binding.tvDownloadSpeed.text = formatSpeed(totalDownload)
                    binding.tvUploadTotal.text = formatBytes(totalUpload)
                    binding.tvDownloadTotal.text = formatBytes(totalDownload)
                }
                statsHandler.postDelayed(this, 1000)
            }
        }
        statsHandler.post(statsRunnable)
    }

    // ===== UI STATE =====

    private fun updateUiState() {
        if (isConnected) {
            binding.tvConnectionStatus.text = getString(R.string.status_connected)
            binding.tvConnectionSubtitle.text = "$currentServerCountry • $currentServerName"
            binding.tvTopIpAddress.text = currentServerIp
            binding.tvConnectionTimer.isVisible = true
            binding.btnQuickConnect.isVisible = false
            binding.btnDisconnect.isVisible = true
            binding.cardNetworkInfo.isVisible = true
            binding.cardTrafficStats.isVisible = true

            // Update network info
            binding.tvConnectedIp.text = currentServerIp
            binding.tvConnectedServer.text = currentServerName
            binding.tvConnectedSpeed.text = formatSpeedMbps(currentServerSpeed)
            binding.tvConnectedPing.text = "${currentServerPing} ms"
        } else if (isConnecting) {
            binding.tvConnectionStatus.text = getString(R.string.status_connecting)
            binding.tvTopIpAddress.text = getString(R.string.status_connecting)
            binding.tvConnectionTimer.isVisible = false
            binding.btnQuickConnect.isVisible = false
            binding.btnDisconnect.isVisible = false
            binding.cardNetworkInfo.isVisible = false
            binding.cardTrafficStats.isVisible = false
        } else {
            binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
            binding.tvConnectionSubtitle.text = getString(R.string.status_hint_disconnected)
            binding.tvTopIpAddress.text = getString(R.string.status_disconnected)
            binding.tvConnectionTimer.isVisible = false
            binding.btnQuickConnect.isVisible = true
            binding.btnDisconnect.isVisible = false
            binding.cardNetworkInfo.isVisible = false
            binding.cardTrafficStats.isVisible = false
        }
    }

    // ===== RECENT SERVER =====

    private fun loadRecentServer() {
        val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("recent_server_name", null)
        if (name != null) {
            binding.cardRecentServer.isVisible = true
            binding.tvRecentServerName.text = name
            binding.tvRecentServerInfo.text = "${prefs.getString("recent_country", "")} • ${prefs.getInt("recent_ping", 0)}ms"
        }
    }

    private fun saveRecentServer() {
        val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("recent_server_name", currentServerName)
            .putString("recent_country", currentServerCountry)
            .putString("recent_ip", currentServerIp)
            .putInt("recent_ping", currentServerPing)
            .putLong("recent_speed", currentServerSpeed)
            .apply()
    }

    // ===== NAVIGATION =====

    private fun navigateToServerList() {
        val intent = Intent(this, ServerListActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun navigateToChat() {
        val intent = Intent(this, ChatActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun navigateToConnected() {
        val intent = Intent(this, ConnectedActivity::class.java).apply {
            putExtra("server_name", currentServerName)
            putExtra("server_ip", currentServerIp)
            putExtra("server_country", currentServerCountry)
            putExtra("server_speed", currentServerSpeed)
            putExtra("server_ping", currentServerPing)
            putExtra("connection_start", connectionStartTime)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    // ===== UTILS =====

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1048576 -> "${bytesPerSecond / 1024} KB/s"
            else -> String.format("%.1f MB/s", bytesPerSecond / 1048576.0)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> "${bytes / 1024} KB"
            bytes < 1073741824 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    private fun formatSpeedMbps(bitsPerSecond: Long): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return String.format("%.1f Mbps", mbps)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllAnimations()
    }
}
