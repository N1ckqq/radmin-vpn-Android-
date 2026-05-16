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
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityMainBinding
import com.radminvpn.android.model.ConnectionState
import com.radminvpn.android.model.PeerInfo
import com.radminvpn.android.vpn.VpnOrchestrator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var orchestrator: VpnOrchestrator

    private var pendingAction: (() -> Unit)? = null
    private var pulseAnimator: AnimatorSet? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        orchestrator = VpnOrchestrator(this)

        setupGrid()
        setupButtons()
        observeState()
        setStatusColor(Color.parseColor("#6E6E7E"))
    }

    private fun applyStoredTheme() {
        val prefs = SettingsActivity.getPrefs(this)
        when (prefs.getString(SettingsActivity.KEY_THEME, SettingsActivity.THEME_DARK)) {
            SettingsActivity.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            SettingsActivity.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SettingsActivity.THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupGrid() {
        // Firebase - create/join via Firebase signaling
        binding.cardFirebase.setOnClickListener {
            // Already on main screen - just scroll to create/join
            binding.layoutActions.visibility = View.VISIBLE
        }

        // Direct IP
        binding.cardDirectIp.setOnClickListener {
            startActivity(Intent(this, DirectIpActivity::class.java))
        }

        // Share Key
        binding.cardShareKey.setOnClickListener {
            startActivity(Intent(this, QrConnectActivity::class.java))
        }

        // Auto Find (NSD)
        binding.cardAutoFind.setOnClickListener {
            startActivity(Intent(this, NsdConnectActivity::class.java))
        }

        // Manual
        binding.cardManual.setOnClickListener {
            startActivity(Intent(this, ManualConnectActivity::class.java))
        }

        // Settings → now opens ServerListActivity (VPS/VDS servers)
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, ServerListActivity::class.java))
        }
    }

    private fun setupButtons() {
        binding.btnSettingsTop.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnCreateNetwork.setOnClickListener {
            requestVpnPermission { orchestrator.createNetwork() }
        }

        binding.btnJoinNetwork.setOnClickListener {
            val networkId = binding.etNetworkId.text.toString().trim().uppercase()
            if (networkId.length != 6) {
                binding.etNetworkId.error = getString(R.string.enter_6_chars)
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
                Toast.makeText(this, getString(R.string.copied, id), Toast.LENGTH_SHORT).show()
            }
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

    private fun updateUI(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatusMessage.text = getString(R.string.status_hint_disconnected)
                setStatusColor(Color.parseColor("#6E6E7E"))
                stopPulseAnimation()

                showWithAnimation(binding.layoutActions)
                hideWithAnimation(binding.btnDisconnect)
                hideWithAnimation(binding.cardNetworkInfo)

                binding.btnCreateNetwork.isEnabled = true
                binding.btnJoinNetwork.isEnabled = true
                binding.etNetworkId.isEnabled = true
            }

            ConnectionState.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                setStatusColor(Color.parseColor("#FFC107"))
                startPulseAnimation()

                binding.btnCreateNetwork.isEnabled = false
                binding.btnJoinNetwork.isEnabled = false
                binding.etNetworkId.isEnabled = false
            }

            ConnectionState.WAITING_FOR_PEERS -> {
                binding.tvStatus.text = getString(R.string.status_waiting_peers)
                setStatusColor(Color.parseColor("#29B6F6"))
                startPulseAnimation()

                hideWithAnimation(binding.layoutActions)
                showWithAnimation(binding.cardNetworkInfo)
                showWithAnimation(binding.btnDisconnect)
            }

            ConnectionState.CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                setStatusColor(Color.parseColor("#00E676"))
                stopPulseAnimation()

                hideWithAnimation(binding.layoutActions)
                showWithAnimation(binding.cardNetworkInfo)
                showWithAnimation(binding.btnDisconnect)

                // Celebrate with a bounce
                binding.viewStatusDot.animate()
                    .scaleX(1.3f).scaleY(1.3f)
                    .setDuration(150)
                    .withEndAction {
                        binding.viewStatusDot.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(150)
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun updatePeerList(peers: List<PeerInfo>) {
        binding.layoutPeers.removeAllViews()
        if (peers.isEmpty()) {
            binding.layoutPeers.isVisible = false
            return
        }

        binding.layoutPeers.isVisible = true
        for (peer in peers) {
            val tv = TextView(this).apply {
                val statusIcon = if (peer.isConnected) "\u2B24" else "\u25CB"
                val iconColor = if (peer.isConnected) "#00E676" else "#6E6E7E"
                text = "$statusIcon  ${peer.virtualIp}  (${peer.peerId.take(8)})"
                textSize = 13f
                setTextColor(Color.parseColor("#E0E0E0"))
                setPadding(0, 6, 0, 6)
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

        val scaleX = ObjectAnimator.ofFloat(binding.viewPulseOuter, "scaleX", 1f, 2.2f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val scaleY = ObjectAnimator.ofFloat(binding.viewPulseOuter, "scaleY", 1f, 2.2f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(binding.viewPulseOuter, "alpha", 0.5f, 0f).apply {
            duration = 1400
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
