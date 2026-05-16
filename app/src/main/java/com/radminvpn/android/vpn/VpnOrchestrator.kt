package com.radminvpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.radminvpn.android.model.ConnectionState
import com.radminvpn.android.model.PeerInfo
import com.radminvpn.android.signaling.FirebaseSignaling
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Orchestrator — connects Firebase Signaling, WebRTC, and VPN Service.
 * Properly manages per-peer WebRTC connections with ICE candidate exchange.
 */
class VpnOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private val signaling = FirebaseSignaling()
    private val webRtcManager = WebRtcManager(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _virtualIp = MutableStateFlow("")
    val virtualIp: StateFlow<String> = _virtualIp

    private val _networkId = MutableStateFlow("")
    val networkId: StateFlow<String> = _networkId

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private var currentNetworkId: String? = null
    private var vpnStarted = false

    init {
        webRtcManager.initialize(createWebRtcListener())
    }

    /**
     * Create a new network (I am the host)
     */
    fun createNetwork() {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Создание сети..."
                VpnLog.i(TAG, "Creating new network...")

                val netId = signaling.createNetwork()
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = "10.0.0.1"

                VpnLog.success(TAG, "Network created: $netId")
                VpnLog.i(TAG, "Your virtual IP: 10.0.0.1")
                VpnLog.i(TAG, "Waiting for peers to join...")

                _statusMessage.value = "Сеть создана! Ожидание участников..."
                _connectionState.value = ConnectionState.WAITING_FOR_PEERS

                // Start listening for peers and signaling
                startListeners(netId)

            } catch (e: Exception) {
                VpnLog.e(TAG, "Create network failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Join an existing network
     */
    fun joinNetwork(netId: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Подключение к сети $netId..."
                VpnLog.i(TAG, "Joining network: $netId")

                val myIp = signaling.joinNetwork(netId)
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = myIp

                VpnLog.success(TAG, "Joined network: $netId")
                VpnLog.i(TAG, "Your virtual IP: $myIp")

                _statusMessage.value = "В сети! IP: $myIp. Устанавливаю P2P..."

                // Start listening for peers and signaling
                startListeners(netId)

                // Small delay to let peer list populate, then connect
                delay(1500)

                // Initiate connection to existing peers
                val existingPeers = _peers.value
                VpnLog.i(TAG, "Found ${existingPeers.size} existing peer(s)")
                for (peer in existingPeers) {
                    initiateP2PConnection(netId, peer.peerId)
                }

            } catch (e: Exception) {
                VpnLog.e(TAG, "Join network failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Start all Firebase listeners
     */
    private fun startListeners(netId: String) {
        // Listen for new peers joining
        scope.launch {
            signaling.listenForPeers(netId).collect { peerInfo ->
                VpnLog.i(TAG, "Peer discovered: ${peerInfo.peerId} (${peerInfo.virtualIp})")
                val currentPeers = _peers.value.toMutableList()
                if (currentPeers.none { it.peerId == peerInfo.peerId }) {
                    currentPeers.add(peerInfo)
                    _peers.value = currentPeers
                }
            }
        }

        // Listen for incoming SDP offers (someone wants to connect to us)
        scope.launch {
            signaling.listenForOffers(netId).collect { (fromPeerId, offer) ->
                VpnLog.i(TAG, "Received SDP offer from $fromPeerId")
                handleIncomingOffer(netId, fromPeerId, offer)
            }
        }

        // Listen for SDP answers to our offers
        scope.launch {
            signaling.listenForAnswers(netId).collect { (fromPeerId, answer) ->
                VpnLog.i(TAG, "Received SDP answer from $fromPeerId")
                handleIncomingAnswer(fromPeerId, answer)
            }
        }

        // Listen for ICE candidates from all peers
        scope.launch {
            signaling.listenForIceCandidates(netId).collect { (fromPeerId, candidateStr) ->
                handleIncomingIceCandidate(fromPeerId, candidateStr)
            }
        }
    }

    /**
     * Initiate a P2P connection to a specific peer (we create the offer)
     */
    private fun initiateP2PConnection(netId: String, targetPeerId: String) {
        VpnLog.i(TAG, "Initiating P2P connection to $targetPeerId...")

        val wrapper = webRtcManager.createConnection(targetPeerId, isInitiator = true) ?: return

        wrapper.createOffer { sdpOffer ->
            scope.launch {
                VpnLog.d(TAG, "Sending SDP offer to $targetPeerId")
                signaling.sendOffer(netId, targetPeerId, sdpOffer)
            }
        }
    }

    /**
     * Handle an incoming SDP offer (we create the answer)
     */
    private fun handleIncomingOffer(netId: String, fromPeerId: String, offer: String) {
        VpnLog.i(TAG, "Handling incoming offer from $fromPeerId")

        // Create connection as answerer
        val wrapper = webRtcManager.createConnection(fromPeerId, isInitiator = false) ?: return

        // Set remote description first
        wrapper.setRemoteDescription(offer, SessionDescription.Type.OFFER)

        // Then create answer
        wrapper.createAnswer { sdpAnswer ->
            scope.launch {
                VpnLog.d(TAG, "Sending SDP answer to $fromPeerId")
                signaling.sendAnswer(netId, fromPeerId, sdpAnswer)
            }
        }
    }

    /**
     * Handle an incoming SDP answer
     */
    private fun handleIncomingAnswer(fromPeerId: String, answer: String) {
        val wrapper = webRtcManager.getConnection(fromPeerId)
        if (wrapper == null) {
            VpnLog.w(TAG, "No connection found for peer $fromPeerId (answer)")
            return
        }
        wrapper.setRemoteDescription(answer, SessionDescription.Type.ANSWER)
    }

    /**
     * Handle an incoming ICE candidate
     */
    private fun handleIncomingIceCandidate(fromPeerId: String, candidateStr: String) {
        val wrapper = webRtcManager.getConnection(fromPeerId)
        if (wrapper == null) {
            VpnLog.w(TAG, "No connection for peer $fromPeerId (ICE candidate) — queuing...")
            // Retry after short delay (connection might not be ready yet)
            scope.launch {
                delay(500)
                val retryWrapper = webRtcManager.getConnection(fromPeerId)
                if (retryWrapper != null) {
                    parseAndAddIceCandidate(retryWrapper, candidateStr)
                } else {
                    VpnLog.e(TAG, "Still no connection for $fromPeerId, dropping ICE candidate")
                }
            }
            return
        }
        parseAndAddIceCandidate(wrapper, candidateStr)
    }

    private fun parseAndAddIceCandidate(
        wrapper: WebRtcManager.PeerConnectionWrapper,
        candidateStr: String
    ) {
        try {
            val parts = candidateStr.split("|", limit = 3)
            if (parts.size == 3) {
                wrapper.addIceCandidate(parts[0], parts[1].toInt(), parts[2])
            } else {
                VpnLog.e(TAG, "Malformed ICE candidate: $candidateStr")
            }
        } catch (e: Exception) {
            VpnLog.e(TAG, "Failed to parse ICE candidate: ${e.message}")
        }
    }

    /**
     * Create WebRTC listener that handles events for all peer connections
     */
    private fun createWebRtcListener(): WebRtcManager.Listener {
        return object : WebRtcManager.Listener {
            override fun onIceCandidate(peerId: String, candidate: IceCandidate) {
                val candidateStr = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                scope.launch {
                    val netId = currentNetworkId ?: return@launch
                    signaling.sendIceCandidate(netId, peerId, candidateStr)
                }
            }

            override fun onDataChannelMessage(peerId: String, data: ByteArray) {
                // Received packet from remote peer → write to TUN
                P2PVpnService.instance?.writePacket(data)
            }

            override fun onPeerConnected(peerId: String) {
                _connectionState.value = ConnectionState.CONNECTED
                _statusMessage.value = "P2P соединение установлено!"
                VpnLog.success(TAG, "Connected to peer: $peerId")

                // Update peer status
                updatePeerConnectionStatus(peerId, true)

                // Start VPN service if not already started
                if (!vpnStarted) {
                    startVpnService()
                }
            }

            override fun onPeerDisconnected(peerId: String) {
                VpnLog.w(TAG, "Peer disconnected: $peerId")
                updatePeerConnectionStatus(peerId, false)

                // Check if all peers are disconnected
                val anyConnected = _peers.value.any { it.isConnected }
                if (!anyConnected && vpnStarted) {
                    _statusMessage.value = "Все пиры отключены"
                }
            }

            override fun onDataChannelOpen(peerId: String) {
                VpnLog.success(TAG, "Tunnel OPEN with $peerId — data can flow!")
                _statusMessage.value = "Туннель активен!"
            }

            override fun onDataChannelClose(peerId: String) {
                VpnLog.w(TAG, "Tunnel CLOSED with $peerId")
            }
        }
    }

    private fun updatePeerConnectionStatus(peerId: String, connected: Boolean) {
        val updated = _peers.value.map { peer ->
            if (peer.peerId == peerId) peer.copy(isConnected = connected) else peer
        }
        _peers.value = updated
    }

    /**
     * Start VPN service (TUN interface)
     */
    private fun startVpnService() {
        val myIp = _virtualIp.value
        if (myIp.isEmpty()) return

        VpnLog.i(TAG, "Starting VPN service with IP: $myIp")

        val intent = Intent(context, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, myIp)
        }
        context.startForegroundService(intent)

        vpnStarted = true

        // Connect TUN output to WebRTC
        scope.launch {
            delay(500) // Wait for service to start
            P2PVpnService.instance?.onPacketReceived = { packet ->
                webRtcManager.sendPacketToAll(packet)
            }
            VpnLog.success(TAG, "VPN tunnel connected to WebRTC")
        }
    }

    /**
     * Disconnect from the network
     */
    fun disconnect() {
        scope.launch {
            VpnLog.i(TAG, "Disconnecting...")

            currentNetworkId?.let {
                try {
                    signaling.leaveNetwork(it)
                } catch (e: Exception) {
                    VpnLog.w(TAG, "Leave network error: ${e.message}")
                }
            }

            // Stop VPN
            val intent = Intent(context, P2PVpnService::class.java).apply {
                action = P2PVpnService.ACTION_STOP
            }
            context.startService(intent)

            // Close all WebRTC connections
            webRtcManager.dispose()

            vpnStarted = false
            _connectionState.value = ConnectionState.DISCONNECTED
            _virtualIp.value = ""
            _networkId.value = ""
            _peers.value = emptyList()
            _statusMessage.value = "Отключено"
            currentNetworkId = null

            VpnLog.i(TAG, "Disconnected successfully")
        }
    }

    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
