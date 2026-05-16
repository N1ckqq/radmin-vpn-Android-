package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.databinding.ActivityMainBinding
import com.radminvpn.android.model.ConnectionState
import com.radminvpn.android.model.LogLevel
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.VpnOrchestrator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var orchestrator: VpnOrchestrator

    private var pendingAction: (() -> Unit)? = null
    private var pulseAnimator: AnimatorSet? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        orchestrator = VpnOrchestrator(this)

        setupButtons()
        observeState()
        observeLogs()
        setStatusColor(Color.parseColor("#9E9E9E")) // Gray = disconnected
    }

    private fun setupButtons() {
        binding.btnCreateNetwork.setOnClickListener {
            requestVpnPermission { orchestrator.createNetwork() }
        }

        binding.btnJoinNetwork.setOnClickListener {
            val networkId = binding.etNetworkId.text.toString().trim().uppercase()
            if (networkId.length != 6) {
                binding.etNetworkId.error = "Enter 6 characters"
                return@setOnClickListener
            }
            requestVpnPermission { orchestrator.joinNetwork(networkId) }
        }

        binding.btnDisconnect.setOnClickListener {
            orchestrator.disconnect()
        }

        binding.btnCopyId.setOnClickListener {
            val id = orchestrator.networkId.value
            if (id.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Network ID", id))
                Toast.makeText(this, "Copied: $id", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            VpnLog.clear()
            binding.tvLogs.text = ""
        }

        binding.btnManualConnect.setOnClickListener {
            startActivity(Intent(this, ManualConnectActivity::class.java))
        }

        binding.btnChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.btnDirectIp.setOnClickListener {
            startActivity(Intent(this, DirectIpActivity::class.java))
        }

        binding.btnQrConnect.setOnClickListener {
            startActivity(Intent(this, QrConnectActivity::class.java))
        }

        binding.btnNsdConnect.setOnClickListener {
            startActivity(Intent(this, NsdConnectActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            orchestrator.connectionState.collect { state ->
                updateUI(state)
            }
        }

        lifecycleScope.launch {
            orchestrator.virtualIp.collect { ip ->
                binding.tvVirtualIp.text = ip.ifEmpty { "-" }
            }
        }

        lifecycleScope.launch {
            orchestrator.networkId.collect { id ->
                binding.tvNetworkId.text = id
            }
        }

        lifecycleScope.launch {
            orchestrator.statusMessage.collect { msg ->
                binding.tvStatusMessage.text = msg
            }
        }

        lifecycleScope.launch {
            orchestrator.peers.collect { peers ->
                binding.tvPeerCount.text = peers.size.toString()
                updatePeerList(peers)
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            VpnLog.logs.collect { logs ->
                if (logs.isEmpty()) {
                    binding.tvLogs.text = "Waiting for connection..."
                    return@collect
                }

                val builder = SpannableStringBuilder()
                // Show last 50 logs
                val recentLogs = logs.takeLast(50)

                for (entry in recentLogs) {
                    val time = timeFormat.format(Date(entry.timestamp))
                    val prefix = when (entry.level) {
                        LogLevel.DEBUG -> "DBG"
                        LogLevel.INFO -> "INF"
                        LogLevel.WARNING -> "WRN"
                        LogLevel.ERROR -> "ERR"
                        LogLevel.SUCCESS -> " OK"
                    }
                    val color = when (entry.level) {
                        LogLevel.DEBUG -> Color.parseColor("#78909C")
                        LogLevel.INFO -> Color.parseColor("#B0BEC5")
                        LogLevel.WARNING -> Color.parseColor("#FFB74D")
                        LogLevel.ERROR -> Color.parseColor("#EF5350")
                        LogLevel.SUCCESS -> Color.parseColor("#66BB6A")
                    }

                    val line = "$time [$prefix] ${entry.message}\n"
                    val start = builder.length
                    builder.append(line)
                    builder.setSpan(
                        ForegroundColorSpan(color),
                        start, start + line.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                binding.tvLogs.text = builder

                // Auto-scroll to bottom
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun updateUI(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = "Disconnected"
                setStatusColor(Color.parseColor("#9E9E9E"))
                stopPulseAnimation()

                showWithAnimation(binding.layoutActions)
                hideWithAnimation(binding.btnDisconnect)
                hideWithAnimation(binding.cardNetworkInfo)

                binding.btnCreateNetwork.isEnabled = true
                binding.btnJoinNetwork.isEnabled = true
                binding.etNetworkId.isEnabled = true
            }

            ConnectionState.CONNECTING -> {
                binding.tvStatus.text = "Connecting..."
                setStatusColor(Color.parseColor("#FFC107"))
                startPulseAnimation()

                binding.btnCreateNetwork.isEnabled = false
                binding.btnJoinNetwork.isEnabled = false
                binding.etNetworkId.isEnabled = false
            }

            ConnectionState.WAITING_FOR_PEERS -> {
                binding.tvStatus.text = "Waiting for peers"
                setStatusColor(Color.parseColor("#29B6F6"))
                startPulseAnimation()

                hideWithAnimation(binding.layoutActions)
                showWithAnimation(binding.cardNetworkInfo)
                showWithAnimation(binding.btnDisconnect)
            }

            ConnectionState.CONNECTED -> {
                binding.tvStatus.text = "Connected"
                setStatusColor(Color.parseColor("#4CAF50"))
                stopPulseAnimation()

                hideWithAnimation(binding.layoutActions)
                showWithAnimation(binding.cardNetworkInfo)
                showWithAnimation(binding.btnDisconnect)

                // Celebrate connection with a bounce
                binding.cardStatus.animate()
                    .scaleX(1.02f).scaleY(1.02f)
                    .setDuration(150)
                    .withEndAction {
                        binding.cardStatus.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(150)
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun updatePeerList(peers: List<com.radminvpn.android.model.PeerInfo>) {
        binding.layoutPeers.removeAllViews()
        if (peers.isEmpty()) {
            binding.layoutPeers.isVisible = false
            return
        }

        binding.layoutPeers.isVisible = true
        for (peer in peers) {
            val tv = TextView(this).apply {
                val statusIcon = if (peer.isConnected) "\uD83D\uDFE2" else "\u26AA"
                text = "$statusIcon  ${peer.virtualIp}  (${peer.peerId})"
                textSize = 13f
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 4, 0, 4)
            }
            binding.layoutPeers.addView(tv)
        }
    }

    private fun setStatusColor(color: Int) {
        val dot = binding.viewStatusDot.background
        if (dot is GradientDrawable) {
            dot.setColor(color)
        } else {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            binding.viewStatusDot.background = shape
        }

        val pulse = binding.viewPulseOuter.background
        if (pulse is GradientDrawable) {
            pulse.setColor(color)
        } else {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            binding.viewPulseOuter.background = shape
        }
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()

        val scaleX = ObjectAnimator.ofFloat(binding.viewPulseOuter, "scaleX", 1f, 2f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val scaleY = ObjectAnimator.ofFloat(binding.viewPulseOuter, "scaleY", 1f, 2f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(binding.viewPulseOuter, "alpha", 0.6f, 0f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewPulseOuter.scaleX = 1f
        binding.viewPulseOuter.scaleY = 1f
        binding.viewPulseOuter.alpha = 0.3f
    }

    private fun showWithAnimation(view: View) {
        if (view.isVisible) return
        view.isVisible = true
        view.alpha = 0f
        view.translationY = 30f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun hideWithAnimation(view: View) {
        if (!view.isVisible) return
        view.animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(250)
            .withEndAction { view.isVisible = false }
            .start()
    }

    private fun requestVpnPermission(action: () -> Unit) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAction = action
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            action()
        }
    }

    override fun onDestroy() {
        orchestrator.destroy()
        stopPulseAnimation()
        super.onDestroy()
    }
}
