package com.radminvpn.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.databinding.ActivityNsdConnectBinding
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

class NsdConnectActivity : AppCompatActivity(), WebRtcManager.Listener {

    companion object {
        private const val TAG = "NsdConnect"
        private const val PEER_ID = "nsd-peer"
        private const val SERVICE_TYPE = "_p2pvpn._tcp."
        private const val SERVICE_NAME = "P2PVPN"
    }

    private lateinit var binding: ActivityNsdConnectBinding
    private lateinit var webRtcManager: WebRtcManager
    private lateinit var nsdManager: NsdManager

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

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isServiceRegistered = false
    private var isDiscoveryActive = false

    data class DiscoveredPeer(
        val serviceName: String,
        val host: String,
        val port: Int
    )

    private val discoveredPeers = mutableListOf<DiscoveredPeer>()

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
        binding = ActivityNsdConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        webRtcManager = WebRtcManager(applicationContext)
        webRtcManager.initialize(this)

        setupUI()
        observeLogs()

        VpnLog.i(TAG, "Auto Discovery activity started")
    }

    private fun setupUI() {
        binding.toggleRole.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isHost = checkedId == binding.btnRoleHost.id
                updateUIForRole()
                resetState()
            }
        }

        binding.btnStartStop.setOnClickListener {
            if (!isRunning) {
                if (isHost) {
                    startHost()
                } else {
                    startDiscovery()
                }
            } else {
                stopAll()
            }
        }

        updateUIForRole()
    }

    private fun updateUIForRole() {
        binding.cardBroadcast.isVisible = false
        binding.cardPeerList.isVisible = false

        if (isHost) {
            binding.btnStartStop.text = "Start Broadcasting"
        } else {
            binding.btnStartStop.text = "Start Searching"
        }
    }

    private fun resetState() {
        stopAll()
        iceCandidates.clear()
        isConnected = false
        discoveredPeers.clear()
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

    // ========== HOST ==========

    private fun startHost() {
        isRunning = true
        binding.btnStartStop.text = "Stop Broadcasting"
        binding.cardBroadcast.isVisible = true
        binding.tvBroadcastStatus.text = "Starting..."
        setStatus("Starting host service...", "#FFC107")
        VpnLog.i(TAG, "Host: Starting NSD service registration...")

        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(0) // Auto-assign port
                val port = serverSocket!!.localPort
                val localIp = getLocalIpAddress() ?: "unknown"

                VpnLog.i(TAG, "Host: ServerSocket on port $port (IP: $localIp)")

                withContext(Dispatchers.Main) {
                    binding.tvServiceName.text = "Port: $port | IP: $localIp"
                }

                registerNsdService(port)

                // Wait for client connection
                VpnLog.i(TAG, "Host: Waiting for peer connection...")
                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket = socket
                    val remoteAddr = socket.inetAddress.hostAddress
                    VpnLog.success(TAG, "Host: Peer connected from $remoteAddr")
                    withContext(Dispatchers.Main) {
                        setStatus("Peer connected! Exchanging signaling...", "#29B6F6")
                        binding.tvBroadcastStatus.text = "Peer connected!"
                    }

                    writer = PrintWriter(socket.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    handleHostSignaling()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    VpnLog.e(TAG, "Host error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        setStatus("Error: ${e.message}", "#D32F2F")
                    }
                }
            }
        }
    }

    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = "_p2pvpn._tcp"
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val registeredName = serviceInfo.serviceName
                VpnLog.success(TAG, "Host: Service registered as '$registeredName'")
                isServiceRegistered = true
                runOnUiThread {
                    binding.tvBroadcastStatus.text = "Broadcasting..."
                    setStatus("Broadcasting as '$registeredName'. Waiting for peer...", "#4CAF50")
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                VpnLog.e(TAG, "Host: Service registration failed (error=$errorCode)")
                runOnUiThread {
                    setStatus("Registration failed (error=$errorCode)", "#D32F2F")
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                VpnLog.i(TAG, "Host: Service unregistered")
                isServiceRegistered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                VpnLog.e(TAG, "Host: Unregistration failed (error=$errorCode)")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // ========== GUEST ==========

    private fun startDiscovery() {
        isRunning = true
        binding.btnStartStop.text = "Stop Searching"
        binding.cardPeerList.isVisible = true
        binding.tvNoPeers.isVisible = true
        binding.layoutPeerList.removeAllViews()
        discoveredPeers.clear()
        setStatus("Searching for peers...", "#FFC107")
        VpnLog.i(TAG, "Guest: Starting NSD discovery...")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                VpnLog.i(TAG, "Guest: Discovery started for $regType")
                isDiscoveryActive = true
                runOnUiThread {
                    setStatus("Searching for peers on local network...", "#29B6F6")
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                VpnLog.i(TAG, "Guest: Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("_p2pvpn._tcp")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                VpnLog.w(TAG, "Guest: Service lost: ${serviceInfo.serviceName}")
                runOnUiThread {
                    discoveredPeers.removeAll { it.serviceName == serviceInfo.serviceName }
                    updatePeerListUI()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                VpnLog.i(TAG, "Guest: Discovery stopped")
                isDiscoveryActive = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                VpnLog.e(TAG, "Guest: Discovery start failed (error=$errorCode)")
                isDiscoveryActive = false
                runOnUiThread {
                    setStatus("Discovery failed (error=$errorCode)", "#D32F2F")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                VpnLog.e(TAG, "Guest: Discovery stop failed (error=$errorCode)")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                VpnLog.e(TAG, "Guest: Resolve failed for ${serviceInfo.serviceName} (error=$errorCode)")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress ?: return
                val port = resolvedInfo.port
                val name = resolvedInfo.serviceName

                VpnLog.success(TAG, "Guest: Resolved '$name' at $host:$port")

                val peer = DiscoveredPeer(name, host, port)
                runOnUiThread {
                    if (discoveredPeers.none { it.host == host && it.port == port }) {
                        discoveredPeers.add(peer)
                        updatePeerListUI()
                    }
                }
            }
        })
    }

    private fun updatePeerListUI() {
        binding.layoutPeerList.removeAllViews()
        binding.tvNoPeers.isVisible = discoveredPeers.isEmpty()

        for (peer in discoveredPeers) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12f * resources.displayMetrics.density
                cardElevation = 2f * resources.displayMetrics.density
                setContentPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = params
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    connectToPeer(peer)
                }
            }

            val textView = TextView(this).apply {
                text = "\uD83D\uDFE2 ${peer.serviceName}\n     ${peer.host}:${peer.port}"
                textSize = 14f
                setTextColor(Color.parseColor("#1565C0"))
            }

            card.addView(textView)
            binding.layoutPeerList.addView(card)
        }
    }

    private fun connectToPeer(peer: DiscoveredPeer) {
        setStatus("Connecting to ${peer.serviceName}...", "#FFC107")
        VpnLog.i(TAG, "Guest: Connecting to ${peer.host}:${peer.port}")

        // Stop discovery once we pick a peer
        stopDiscovery()

        clientJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(peer.host, peer.port)
                clientSocket = socket
                VpnLog.success(TAG, "Guest: TCP connected to host")
                withContext(Dispatchers.Main) {
                    setStatus("Connected to ${peer.serviceName}! Exchanging signaling...", "#29B6F6")
                }

                writer = PrintWriter(socket.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                handleGuestSignaling()
            } catch (e: Exception) {
                VpnLog.e(TAG, "Guest: Connection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    setStatus("Connection failed: ${e.message}", "#D32F2F")
                }
            }
        }
    }

    // ========== SIGNALING (same as DirectIpActivity) ==========

    private suspend fun handleHostSignaling() {
        VpnLog.i(TAG, "Host: Waiting for offer from guest...")

        val wrapper = webRtcManager.createConnection(PEER_ID, isInitiator = false)
        if (wrapper == null) {
            VpnLog.e(TAG, "Host: Failed to create PeerConnection")
            return
        }

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

    // ========== CLEANUP ==========

    private fun stopDiscovery() {
        if (isDiscoveryActive && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                VpnLog.w(TAG, "Stop discovery error: ${e.message}")
            }
            isDiscoveryActive = false
        }
    }

    private fun unregisterService() {
        if (isServiceRegistered && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                VpnLog.w(TAG, "Unregister service error: ${e.message}")
            }
            isServiceRegistered = false
        }
    }

    private fun stopAll() {
        isRunning = false
        serverJob?.cancel()
        clientJob?.cancel()
        serverJob = null
        clientJob = null

        stopDiscovery()
        unregisterService()

        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        writer = null
        reader = null
        clientSocket = null
        serverSocket = null

        binding.cardBroadcast.isVisible = false
        binding.cardPeerList.isVisible = false

        if (isHost) {
            binding.btnStartStop.text = "Start Broadcasting"
        } else {
            binding.btnStartStop.text = "Start Searching"
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
                    binding.tvLogs.text = "Ready for auto discovery..."
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
        stopAll()
        if (!isConnected) {
            webRtcManager.closeConnection(PEER_ID)
            webRtcManager.dispose()
        }
        super.onDestroy()
    }
}
