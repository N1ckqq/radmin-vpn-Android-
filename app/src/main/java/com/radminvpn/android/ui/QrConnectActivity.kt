package com.radminvpn.android.ui

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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.databinding.ActivityQrConnectBinding
import com.radminvpn.android.model.LogLevel
import com.radminvpn.android.signaling.ManualSignaling
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.P2PVpnService
import com.radminvpn.android.webrtc.WebRtcManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.text.SimpleDateFormat
import java.util.*

class QrConnectActivity : AppCompatActivity(), WebRtcManager.Listener {

    companion object {
        private const val TAG = "ShareConnect"
        private const val PEER_ID = "share-connect-peer"
    }

    private lateinit var binding: ActivityQrConnectBinding
    private lateinit var webRtcManager: WebRtcManager

    private var isHost = true
    private var localSdp: String? = null
    private val iceCandidates = mutableListOf<String>()
    private var connectionKey: String? = null
    private var isConnected = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            VpnLog.w(TAG, "VPN permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        webRtcManager = WebRtcManager(applicationContext)
        webRtcManager.initialize(this)

        setupUI()
        observeLogs()
        handleIncomingIntent(intent)

        VpnLog.i(TAG, "Share connect activity started")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleIncomingIntent(intent)
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText != null && sharedText.isNotBlank()) {
            // Received a key via share intent
            VpnLog.i(TAG, "Received key via share intent")
            binding.toggleRole.check(binding.btnRoleGuest.id)
            isHost = false
            updateUIForRole()
            binding.etPeerKey.setText(sharedText.trim())
        }
    }

    private fun setupUI() {
        binding.toggleRole.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isHost = checkedId == binding.btnRoleHost.id
                updateUIForRole()
                resetState()
            }
        }

        binding.btnGenerate.setOnClickListener {
            if (isHost) {
                generateHostKey()
            }
        }

        binding.btnProcess.setOnClickListener {
            val peerKeyText = binding.etPeerKey.text.toString().trim()
            if (peerKeyText.isEmpty()) {
                binding.etPeerKey.error = "Paste the peer's key"
                return@setOnClickListener
            }

            if (isHost) {
                processGuestAnswer(peerKeyText)
            } else {
                processHostOffer(peerKeyText)
            }
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                binding.etPeerKey.setText(text)
                VpnLog.i(TAG, "Pasted ${text.length} chars from clipboard")
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShareKey.setOnClickListener {
            val key = connectionKey
            if (key != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, key)
                    putExtra(Intent.EXTRA_SUBJECT, "P2P VPN Connection Key")
                }
                startActivity(Intent.createChooser(shareIntent, "Share connection key via"))
                VpnLog.i(TAG, "Sharing connection key via Android share sheet")
            }
        }

        binding.btnCopyKey.setOnClickListener {
            val key = connectionKey
            if (key != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Connection Key", key))
                Toast.makeText(this, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
                VpnLog.i(TAG, "Connection key copied to clipboard")
            }
        }

        updateUIForRole()
    }

    private fun updateUIForRole() {
        if (isHost) {
            binding.btnGenerate.isVisible = true
            binding.btnGenerate.text = if (localSdp == null) "Generate Key" else "Regenerate Key"
            binding.tvInputLabel.text = "After guest sends their answer, paste it here:"
            binding.btnProcess.text = "Apply Answer"
        } else {
            binding.btnGenerate.isVisible = false
            binding.tvInputLabel.text = "Paste the host's key below:"
            binding.btnProcess.text = "Process & Generate Answer"
        }
    }

    private fun resetState() {
        localSdp = null
        iceCandidates.clear()
        connectionKey = null
        isConnected = false
        binding.cardOutput.isVisible = false
        binding.etPeerKey.text?.clear()
        setStatus("Generate key to share", "#9E9E9E")
    }

    private fun generateHostKey() {
        setStatus("Generating WebRTC offer...", "#FFC107")
        VpnLog.i(TAG, "Host: Creating WebRTC connection and generating offer...")

        val wrapper = webRtcManager.createConnection(PEER_ID, isInitiator = true)
        if (wrapper == null) {
            setStatus("Failed to create connection", "#D32F2F")
            VpnLog.e(TAG, "Failed to create PeerConnection")
            return
        }

        wrapper.createOffer { sdpDescription ->
            localSdp = sdpDescription
            VpnLog.i(TAG, "Host: Offer generated, waiting for ICE candidates...")
            setStatus("Offer generated. Collecting ICE candidates...", "#FFC107")

            binding.root.postDelayed({
                buildAndShowKey()
                updateUIForRole()
            }, 2000)
        }
    }

    private fun processHostOffer(peerKeyText: String) {
        setStatus("Processing host's offer...", "#FFC107")
        VpnLog.i(TAG, "Guest: Processing host's key...")

        val parsed = ManualSignaling.parseConnectionKey(peerKeyText)
        if (parsed == null) {
            setStatus("Invalid key format", "#D32F2F")
            VpnLog.e(TAG, "Failed to parse host's key")
            binding.etPeerKey.error = "Invalid key format"
            return
        }

        val (remoteSdp, remoteCandidates) = parsed

        val wrapper = webRtcManager.createConnection(PEER_ID, isInitiator = false)
        if (wrapper == null) {
            setStatus("Failed to create connection", "#D32F2F")
            VpnLog.e(TAG, "Failed to create PeerConnection")
            return
        }

        wrapper.setRemoteDescription(remoteSdp, SessionDescription.Type.OFFER)
        VpnLog.i(TAG, "Guest: Remote description set (offer)")

        for (candidateStr in remoteCandidates) {
            if (candidateStr.isNotBlank()) {
                val parts = candidateStr.split("||")
                if (parts.size == 3) {
                    wrapper.addIceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
                }
            }
        }
        VpnLog.i(TAG, "Guest: Added ${remoteCandidates.size} remote ICE candidates")

        wrapper.createAnswer { sdpDescription ->
            localSdp = sdpDescription
            VpnLog.i(TAG, "Guest: Answer generated, collecting ICE candidates...")
            setStatus("Answer generated. Collecting ICE candidates...", "#FFC107")

            binding.root.postDelayed({
                buildAndShowKey()
                setStatus("Answer ready! Share it back to host.", "#4CAF50")
            }, 2000)
        }
    }

    private fun processGuestAnswer(peerKeyText: String) {
        setStatus("Processing guest's answer...", "#FFC107")
        VpnLog.i(TAG, "Host: Processing guest's answer key...")

        val parsed = ManualSignaling.parseConnectionKey(peerKeyText)
        if (parsed == null) {
            setStatus("Invalid key format", "#D32F2F")
            VpnLog.e(TAG, "Failed to parse guest's answer key")
            binding.etPeerKey.error = "Invalid key format"
            return
        }

        val (remoteSdp, remoteCandidates) = parsed
        val wrapper = webRtcManager.getConnection(PEER_ID)
        if (wrapper == null) {
            setStatus("Connection lost. Regenerate key.", "#D32F2F")
            VpnLog.e(TAG, "PeerConnection not found")
            return
        }

        wrapper.setRemoteDescription(remoteSdp, SessionDescription.Type.ANSWER)
        VpnLog.i(TAG, "Host: Remote description set (answer)")

        for (candidateStr in remoteCandidates) {
            if (candidateStr.isNotBlank()) {
                val parts = candidateStr.split("||")
                if (parts.size == 3) {
                    wrapper.addIceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
                }
            }
        }
        VpnLog.i(TAG, "Host: Added ${remoteCandidates.size} remote ICE candidates")
        setStatus("Answer applied, establishing connection...", "#29B6F6")
    }

    private fun buildAndShowKey() {
        val sdp = localSdp ?: return
        val candidateStrings = iceCandidates.toList()
        connectionKey = ManualSignaling.createConnectionKey(sdp, candidateStrings)

        runOnUiThread {
            binding.cardOutput.isVisible = true
            binding.tvOutputKey.text = connectionKey
            if (isHost) {
                binding.tvOutputLabel.text = "Your offer key (share with guest):"
                setStatus("Key ready! Share with guest via messenger.", "#4CAF50")
            } else {
                binding.tvOutputLabel.text = "Your answer key (share back to host):"
            }
            VpnLog.success(TAG, "Connection key generated (${connectionKey?.length ?: 0} chars)")
        }
    }

    private fun setStatus(message: String, color: String) {
        runOnUiThread {
            binding.tvStatus.text = message
            val dot = binding.viewStatusDot.background
            if (dot is GradientDrawable) {
                dot.setColor(Color.parseColor(color))
            } else {
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(color))
                }
                binding.viewStatusDot.background = shape
            }
        }
    }

    private fun startVpnService() {
        VpnLog.i(TAG, "Starting VPN service...")
        val intent = Intent(this, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, if (isHost) "10.0.0.1" else "10.0.0.2")
        }
        startService(intent)

        P2PVpnService.instance?.onPacketReceived = { packet ->
            webRtcManager.sendPacketToAll(packet)
        }

        VpnLog.success(TAG, "VPN service started!")
        setStatus("Connected! VPN tunnel active.", "#4CAF50")
    }

    private fun requestVpnPermissionAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            VpnLog.logs.collect { logs ->
                if (logs.isEmpty()) {
                    binding.tvLogs.text = "Ready for share connect..."
                    return@collect
                }

                val builder = SpannableStringBuilder()
                val recentLogs = logs.takeLast(30)

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
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    // --- WebRtcManager.Listener ---

    override fun onIceCandidate(peerId: String, candidate: IceCandidate) {
        val encoded = "${candidate.sdpMid}||${candidate.sdpMLineIndex}||${candidate.sdp}"
        iceCandidates.add(encoded)
        VpnLog.d(TAG, "ICE candidate collected (total: ${iceCandidates.size})")
    }

    override fun onDataChannelMessage(peerId: String, data: ByteArray) {
        P2PVpnService.instance?.writePacket(data)
    }

    override fun onPeerConnected(peerId: String) {
        isConnected = true
        VpnLog.success(TAG, "Peer connected: $peerId")
        setStatus("Peer connected! Setting up VPN...", "#4CAF50")
    }

    override fun onPeerDisconnected(peerId: String) {
        isConnected = false
        VpnLog.w(TAG, "Peer disconnected: $peerId")
        setStatus("Peer disconnected", "#D32F2F")
    }

    override fun onDataChannelOpen(peerId: String) {
        VpnLog.success(TAG, "DataChannel open! Starting VPN service...")
        runOnUiThread {
            setStatus("DataChannel open! Requesting VPN permission...", "#4CAF50")
            requestVpnPermissionAndStart()
        }
    }

    override fun onDataChannelClose(peerId: String) {
        VpnLog.w(TAG, "DataChannel closed")
        setStatus("DataChannel closed", "#D32F2F")
    }

    override fun onDestroy() {
        if (!isConnected) {
            webRtcManager.closeConnection(PEER_ID)
            webRtcManager.dispose()
        }
        super.onDestroy()
    }
}
