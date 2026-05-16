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
import com.radminvpn.android.databinding.ActivityDirectIpBinding
import com.radminvpn.android.model.LogLevel
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.P2PVpnService
import com.radminvpn.android.webrtc.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class DirectIpActivity : AppCompatActivity(), WebRtcManager.Listener {

    companion object {
        private const val TAG = "DirectIP"
        private const val PEER_ID = "direct-ip-peer"
        private const val SIGNALING_PORT = 9876
    }

    private lateinit var binding: ActivityDirectIpBinding
    private lateinit var webRtcManager: WebRtcManager

    private var isHost = true
    private var isRunning = false
    private var isConnected = false
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private val iceCandidates = mutableListOf<String>()

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
        binding = ActivityDirectIpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        webRtcManager = WebRtcManager(applicationContext)
        webRtcManager.initialize(this)

        setupUI()
        observeLogs()

        VpnLog.i(TAG, "Direct IP activity started")
    }

    private fun setupUI() {
        binding.toggleRole.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isHost = checkedId == binding.btnRoleHost.id
                updateUIForRole()
                resetState()
            }
        }

        binding.btnStartHost.setOnClickListener {
            if (!isRunning) {
                if (isHost) {
                    startHostServer()
                }
            } else {
                stopConnection()
            }
        }

        binding.btnConnect.setOnClickListener {
            val ip = binding.etHostIp.text.toString().trim()
            if (ip.isEmpty()) {
                binding.etHostIp.error = "Enter host IP address"
                return@setOnClickListener
            }
            connectToHost(ip)
        }

        binding.btnCopyAddress.setOnClickListener {
            val address = binding.tvHostAddress.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Host Address", address))
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
            VpnLog.i(TAG, "Host address copied to clipboard")
        }

        updateUIForRole()
    }

    private fun updateUIForRole() {
        if (isHost) {
            binding.cardHostInfo.isVisible = false
            binding.cardGuestInput.isVisible = false
            binding.btnStartHost.text = "Start Listening"
            binding.btnStartHost.isVisible = true
        } else {
            binding.cardHostInfo.isVisible = false
            binding.cardGuestInput.isVisible = true
            binding.btnStartHost.isVisible = false
        }
    }

    private fun resetState() {
        stopConnection()
        iceCandidates.clear()
        isConnected = false
        setStatus("Ready", "#9E9E9E")
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            VpnLog.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }

    private fun startHostServer() {
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            setStatus("No WiFi connection found", "#D32F2F")
            VpnLog.e(TAG, "Cannot determine local IP address. Are you connected to WiFi?")
            return
        }

        isRunning = true
        binding.btnStartHost.text = "Stop Listening"
        binding.cardHostInfo.isVisible = true
        binding.tvHostAddress.text = "$localIp:$SIGNALING_PORT"
        setStatus("Listening for connections...", "#FFC107")
        VpnLog.i(TAG, "Host: Starting server on $localIp:$SIGNALING_PORT")

        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SIGNALING_PORT)
                VpnLog.i(TAG, "Host: ServerSocket bound to port $SIGNALING_PORT")

                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket = socket
                    val remoteAddr = socket.inetAddress.hostAddress
                    VpnLog.success(TAG, "Host: Client connected from $remoteAddr")
                    withContext(Dispatchers.Main) {
                        setStatus("Peer connected! Exchanging signaling...", "#29B6F6")
                    }

                    writer = PrintWriter(socket.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // Host waits for offer from guest, then sends answer
                    handleHostSignaling()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    VpnLog.e(TAG, "Host server error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        setStatus("Server error: ${e.message}", "#D32F2F")
                    }
                }
            }
        }
    }

    private fun connectToHost(hostIp: String) {
        isRunning = true
        binding.btnConnect.isEnabled = false
        setStatus("Connecting to $hostIp:$SIGNALING_PORT...", "#FFC107")
        VpnLog.i(TAG, "Guest: Connecting to $hostIp:$SIGNALING_PORT")

        clientJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(hostIp, SIGNALING_PORT)
                clientSocket = socket
                VpnLog.success(TAG, "Guest: Connected to host")
                withContext(Dispatchers.Main) {
                    setStatus("Connected! Exchanging signaling...", "#29B6F6")
                }

                writer = PrintWriter(socket.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Guest creates offer and sends it to host
                handleGuestSignaling()
            } catch (e: Exception) {
                VpnLog.e(TAG, "Guest connection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    setStatus("Connection failed: ${e.message}", "#D32F2F")
                    binding.btnConnect.isEnabled = true
                }
                isRunning = false
            }
        }
    }

    private suspend fun handleHostSignaling() {
        // Host: wait for offer from guest
        VpnLog.i(TAG, "Host: Waiting for offer from guest...")

        val wrapper = webRtcManager.createConnection(PEER_ID, isInitiator = false)
        if (wrapper == null) {
            VpnLog.e(TAG, "Host: Failed to create PeerConnection")
            return
        }

        // Read messages from socket
        readSignalingMessages { json ->
            val type = json.getString("type")
            val data = json.getString("data")

            when (type) {
                "offer" -> {
                    VpnLog.i(TAG, "Host: Received offer from guest")
                    wrapper.setRemoteDescription(data, SessionDescription.Type.OFFER)

                    wrapper.createAnswer { answerSdp ->
                        VpnLog.i(TAG, "Host: Answer created, sending to guest")
                        sendSignalingMessage("answer", answerSdp)
                    }
                }
                "ice" -> {
                    VpnLog.d(TAG, "Host: Received ICE candidate from guest")
                    val parts = data.split("||")
                    if (parts.size == 3) {
                        wrapper.addIceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
                    }
                }
            }
        }
    }

    private suspend fun handleGuestSignaling() {
        // Guest: create offer and send to host
        VpnLog.i(TAG, "Guest: Creating WebRTC offer...")

        val wrapper = webRtcManager.createConnection(PEER_ID, isInitiator = true)
        if (wrapper == null) {
            VpnLog.e(TAG, "Guest: Failed to create PeerConnection")
            return
        }

        wrapper.createOffer { offerSdp ->
            VpnLog.i(TAG, "Guest: Offer created, sending to host")
            sendSignalingMessage("offer", offerSdp)
        }

        // Read messages from socket
        readSignalingMessages { json ->
            val type = json.getString("type")
            val data = json.getString("data")

            when (type) {
                "answer" -> {
                    VpnLog.i(TAG, "Guest: Received answer from host")
                    wrapper.setRemoteDescription(data, SessionDescription.Type.ANSWER)
                }
                "ice" -> {
                    VpnLog.d(TAG, "Guest: Received ICE candidate from host")
                    val parts = data.split("||")
                    if (parts.size == 3) {
                        wrapper.addIceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
                    }
                }
            }
        }
    }

    private suspend fun readSignalingMessages(onMessage: suspend (JSONObject) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val r = reader ?: return@withContext
                while (isRunning) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val json = JSONObject(line)
                        onMessage(json)
                    } catch (e: Exception) {
                        VpnLog.w(TAG, "Invalid signaling message: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    VpnLog.e(TAG, "Read error: ${e.message}")
                }
            }
        }
    }

    private fun sendSignalingMessage(type: String, data: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("type", type)
                    put("data", data)
                }
                writer?.println(json.toString())
                VpnLog.d(TAG, "Sent signaling message: type=$type")
            } catch (e: Exception) {
                VpnLog.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    private fun stopConnection() {
        isRunning = false
        serverJob?.cancel()
        clientJob?.cancel()
        serverJob = null
        clientJob = null

        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        writer = null
        reader = null
        clientSocket = null
        serverSocket = null

        binding.btnStartHost.text = "Start Listening"
        binding.cardHostInfo.isVisible = false
        binding.btnConnect.isEnabled = true
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
                    binding.tvLogs.text = "Ready for direct IP connection..."
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
        sendSignalingMessage("ice", encoded)
        VpnLog.d(TAG, "ICE candidate sent to peer (total: ${iceCandidates.size})")
    }

    override fun onDataChannelMessage(peerId: String, data: ByteArray) {
        P2PVpnService.instance?.writePacket(data)
    }

    override fun onPeerConnected(peerId: String) {
        isConnected = true
        VpnLog.success(TAG, "Peer connected via WebRTC!")
        setStatus("Peer connected! Setting up VPN...", "#4CAF50")
    }

    override fun onPeerDisconnected(peerId: String) {
        isConnected = false
        VpnLog.w(TAG, "Peer disconnected")
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
        stopConnection()
        if (!isConnected) {
            webRtcManager.closeConnection(PEER_ID)
            webRtcManager.dispose()
        }
        super.onDestroy()
    }
}
