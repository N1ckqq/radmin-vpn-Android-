package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityConnectedBinding
import com.radminvpn.android.vpn.VpnGateRepository
import kotlinx.coroutines.launch

class ConnectedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectedBinding

    // Server info from intent
    private var serverName = ""
    private var serverIp = ""
    private var serverCountry = ""
    private var serverCountryShort = ""
    private var serverSpeed = 0L
    private var serverPing = 0
    private var serverSessions = 0
    private var serverUptime = 0L
    private var serverScore = 0
    private var connectionStartTime = 0L

    // Traffic stats
    private var totalUpload = 0L
    private var totalDownload = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val statsHandler = Handler(Looper.getMainLooper())

    // Animation
    private var orbAnimator: AnimatorSet? = null
    private var generatedKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractIntentData()
        setupUi()
        setupClickListeners()
        startAnimations()
        startTimer()
        startStatsUpdater()
    }

    // ===== INTENT DATA =====

    private fun extractIntentData() {
        serverName = intent.getStringExtra("server_name") ?: "Unknown Server"
        serverIp = intent.getStringExtra("server_ip") ?: "0.0.0.0"
        serverCountry = intent.getStringExtra("server_country") ?: "Unknown"
        serverCountryShort = intent.getStringExtra("server_country_short") ?: "XX"
        serverSpeed = intent.getLongExtra("server_speed", 0L)
        serverPing = intent.getIntExtra("server_ping", 0)
        serverSessions = intent.getIntExtra("server_sessions", 0)
        serverUptime = intent.getLongExtra("server_uptime", 0L)
        serverScore = intent.getIntExtra("server_score", 0)
        connectionStartTime = intent.getLongExtra("connection_start", System.currentTimeMillis())
    }

    // ===== UI SETUP =====

    private fun setupUi() {
        binding.tvServerName.text = serverName
        binding.tvServerCountry.text = serverCountry
        binding.tvServerIp.text = serverIp
        binding.tvServerSpeed.text = formatSpeedMbps(serverSpeed)
        binding.tvServerPing.text = "${serverPing}ms"
        binding.tvServerUptime.text = formatUptime(serverUptime)
        binding.tvServerFlag.text = getCountryFlag(serverCountryShort)
    }

    // ===== CLICK LISTENERS =====

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            animateButtonPress(it)
            finish()
            overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
        }

        binding.btnGenerateShareKey.setOnClickListener {
            animateButtonPress(it)
            generateShareKey()
        }

        binding.btnCopyKey.setOnClickListener {
            animateButtonPress(it)
            copyKeyToClipboard()
        }

        binding.btnShareKey.setOnClickListener {
            animateButtonPress(it)
            shareKey()
        }

        binding.btnJoinConnect.setOnClickListener {
            animateButtonPress(it)
            val key = binding.etJoinKey.text.toString().trim()
            if (key.isNotEmpty()) {
                joinWithKey(key)
            } else {
                Toast.makeText(this, getString(R.string.enter_key_first), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            animateButtonPress(it)
            showDisconnectDialog()
        }
    }

    // ===== ANIMATIONS =====

    private fun startAnimations() {
        // Connected orb pulse
        val pulseOuterX = ObjectAnimator.ofFloat(binding.viewConnectedPulseOuter, "scaleX", 1f, 1.2f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseOuterY = ObjectAnimator.ofFloat(binding.viewConnectedPulseOuter, "scaleY", 1f, 1.2f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseInnerX = ObjectAnimator.ofFloat(binding.viewConnectedPulseInner, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseInnerY = ObjectAnimator.ofFloat(binding.viewConnectedPulseInner, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alphaOuter = ObjectAnimator.ofFloat(binding.viewConnectedPulseOuter, "alpha", 0.2f, 0.5f, 0.2f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
        }
        val alphaInner = ObjectAnimator.ofFloat(binding.viewConnectedPulseInner, "alpha", 0.5f, 0.8f, 0.5f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
        }

        orbAnimator = AnimatorSet().apply {
            playTogether(pulseOuterX, pulseOuterY, pulseInnerX, pulseInnerY, alphaOuter, alphaInner)
            start()
        }

        // Entrance animation for status indicator
        binding.viewStatusIndicator.animate()
            .scaleX(1.3f).scaleY(1.3f).setDuration(500)
            .withEndAction {
                binding.viewStatusIndicator.animate()
                    .scaleX(1f).scaleY(1f).setDuration(300).start()
            }.start()
    }

    private fun animateButtonPress(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.93f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.93f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.93f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.93f, 1f)
            )
            duration = 120
            interpolator = OvershootInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    // ===== GENERATE KEY =====

    private fun generateShareKey() {
        // Create a Base64 key from server config
        val keyData = "$serverName|$serverIp|$serverCountry|$serverSpeed|$serverPing|${System.currentTimeMillis()}"
        generatedKey = Base64.encodeToString(keyData.toByteArray(), Base64.NO_WRAP)

        // Show key display with animation
        binding.cardKeyDisplay.isVisible = true
        binding.cardKeyDisplay.alpha = 0f
        binding.cardKeyDisplay.translationY = 20f
        binding.cardKeyDisplay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()

        binding.tvShareKey.text = generatedKey

        // Animate the generate button
        binding.btnGenerateShareKey.animate()
            .scaleX(0.95f).scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                binding.btnGenerateShareKey.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }.start()

        Toast.makeText(this, getString(R.string.key_generated), Toast.LENGTH_SHORT).show()
    }

    // ===== COPY KEY =====

    private fun copyKeyToClipboard() {
        if (generatedKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.generate_key_first), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VPN Share Key", generatedKey)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, getString(R.string.key_copied), Toast.LENGTH_SHORT).show()

        // Visual feedback
        binding.btnCopyKey.animate()
            .scaleX(1.1f).scaleY(1.1f).setDuration(100)
            .withEndAction {
                binding.btnCopyKey.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
    }

    // ===== SHARE KEY =====

    private fun shareKey() {
        if (generatedKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.generate_key_first), Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_key_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_key_body, generatedKey))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)))
    }

    // ===== JOIN WITH KEY =====

    private fun joinWithKey(encodedKey: String) {
        try {
            val decoded = String(Base64.decode(encodedKey, Base64.DEFAULT))
            val parts = decoded.split("|")
            if (parts.size >= 3) {
                Toast.makeText(this, getString(R.string.connecting_to_peer, parts[0]), Toast.LENGTH_SHORT).show()
                // In production: initiate connection to the peer's server
                binding.etJoinKey.text?.clear()
            } else {
                Toast.makeText(this, getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
        }
    }

    // ===== DISCONNECT =====

    private fun showDisconnectDialog() {
        AlertDialog.Builder(this, R.style.Theme_RadminVPN_Dialog)
            .setTitle(R.string.disconnect_confirm_title)
            .setMessage(R.string.disconnect_confirm_message)
            .setPositiveButton(R.string.disconnect) { _, _ ->
                performDisconnect()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDisconnect() {
        // Animate orb shrink
        binding.viewConnectedOrb.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                finish()
                overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
            }.start()

        binding.viewConnectedPulseOuter.animate().alpha(0f).setDuration(300).start()
        binding.viewConnectedPulseInner.animate().alpha(0f).setDuration(300).start()
    }

    // ===== TIMER =====

    private fun startTimer() {
        val timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val hours = (elapsed / 3600000).toInt()
                val minutes = ((elapsed % 3600000) / 60000).toInt()
                val seconds = ((elapsed % 60000) / 1000).toInt()
                binding.tvTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable)
    }

    // ===== STATS =====

    private fun startStatsUpdater() {
        val statsRunnable = object : Runnable {
            override fun run() {
                // Simulate realistic traffic
                val uploadDelta = (Math.random() * 80000 + 5000).toLong()
                val downloadDelta = (Math.random() * 200000 + 10000).toLong()
                totalUpload += uploadDelta
                totalDownload += downloadDelta

                binding.tvUploadSpeed.text = formatSpeed(uploadDelta)
                binding.tvDownloadSpeed.text = formatSpeed(downloadDelta)
                binding.tvUploadTotal.text = "Total: ${formatBytes(totalUpload)}"
                binding.tvDownloadTotal.text = "Total: ${formatBytes(totalDownload)}"

                statsHandler.postDelayed(this, 1000)
            }
        }
        statsHandler.post(statsRunnable)
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

    private fun formatUptime(ms: Long): String {
        val hours = ms / 3600000
        return if (hours > 24) "${hours / 24}d" else "${hours}h"
    }

    private fun getCountryFlag(countryCode: String): String {
        return try {
            val first = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
            val second = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
            String(Character.toChars(first)) + String(Character.toChars(second))
        } catch (e: Exception) {
            "🌐"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orbAnimator?.cancel()
        timerHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
    }
}
