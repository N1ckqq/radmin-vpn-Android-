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

class VpnOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private val signaling = FirebaseSignaling()
    private var webRtcManager: WebRtcManager? = null
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
    private val processedIceCandidates = mutableSetOf<String>()
    private val connectingPeers = mutableSetOf<String>()

    private fun ensureWebRtcInitialized() {
        if (webRtcManager == null) {
            webRtcManager = WebRtcManager(context)
            webRtcManager!!.initialize(createWebRtcListener())
            VpnLog.i(TAG, "WebRTC initialized")
        }
    }

    fun createNetwork() {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Создание сети..."
                VpnLog.i(TAG, "Creating new network...")
                ensureWebRtcInitialized()

                val netId = signaling.createNetwork()
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = "10.0.0.1"

                VpnLog.success(TAG, "Network created: $netId")
                VpnLog.i(TAG, "Your IP: 10.0.0.1. Waiting for peers...")
                _statusMessage.value = "Сеть: $netId. Ожидание..."
                _connectionState.value = ConnectionState.WAITING_FOR_PEERS

                startListeners(netId)
            } catch (e: Exception) {
                VpnLog.e(TAG, "Create failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun joinNetwork(netId: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Подключение к $netId..."
                VpnLog.i(TAG, "Joining network: $netId")
                ensureWebRtcInitialized()

                val myIp = signaling.joinNetwork(netId)
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = myIp

                VpnLog.success(TAG, "Joined: $netId, IP: $myIp")
                _statusMessage.value = "В сети! IP: $myIp"
                _connectionState.value = ConnectionState.WAITING_FOR_PEERS

                startListeners(netId)
            } catch (e: Exception) {
                VpnLog.e(TAG, "Join failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun startListeners(netId: String) {
        // When a peer is discovered, initiate P2P
        scope.launch {
            signaling.listenForPeers(netId).collect { peerInfo ->
                VpnLog.i(TAG, "Peer found: ${peerInfo.peerId} (${peerInfo.virtualIp})")
                val list = _peers.value.toMutableList()
                if (list.none { it.peerId == peerInfo.peerId }) {
                    list.add(peerInfo)
                    _peers.value = list
                    // Deterministic: smaller peerId sends offer
                    maybeInitiateConnection(netId, peerInfo.peerId)
                }
            }
        }

        scope.launch {
            signaling.listenForOffers(netId).collect { (fromPeerId, offer) ->
                VpnLog.i(TAG, "Got offer from $fromPeerId")
                handleIncomingOffer(netId, fromPeerId, offer)
            }
        }

        scope.launch {
            signaling.listenForAnswers(netId).collect { (fromPeerId, answer) ->
                VpnLog.i(TAG, "Got answer from $fromPeerId")
                handleIncomingAnswer(fromPeerId, answer)
            }
        }

        scope.launch {
            signaling.listenForIceCandidates(netId).collect { (fromPeerId, candidate) ->
                val key = "$fromPeerId:$candidate"
                if (processedIceCandidates.add(key)) {
                    handleIncomingIceCandidate(fromPeerId, candidate)
                }
            }
        }
    }

    private fun maybeInitiateConnection(netId: String, remotePeerId: String) {
        if (connectingPeers.contains(remotePeerId)) return
        connectingPeers.add(remotePeerId)

        val myId = signaling.localPeerId
        if (myId < remotePeerId) {
            VpnLog.i(TAG, "I initiate (my=$myId < remote=$remotePeerId)")
            initiateP2PConnection(netId, remotePeerId)
        } else {
            VpnLog.i(TAG, "Waiting for offer from $remotePeerId")
        }
    }

    private fun initiateP2PConnection(netId: String, targetPeerId: String) {
        val rtc = webRtcManager ?: return
        val wrapper = rtc.createConnection(targetPeerId, isInitiator = true) ?: return
        wrapper.createOffer { sdp ->
            scope.launch {
                VpnLog.d(TAG, "Sending offer to $targetPeerId")
                signaling.sendOffer(netId, targetPeerId, sdp)
            }
        }
    }

    private fun handleIncomingOffer(netId: String, fromPeerId: String, offer: String) {
        val rtc = webRtcManager ?: return
        if (rtc.getConnection(fromPeerId) != null) {
            VpnLog.w(TAG, "Duplicate offer from $fromPeerId, ignoring")
            return
        }
        connectingPeers.add(fromPeerId)
        val wrapper = rtc.createConnection(fromPeerId, isInitiator = false) ?: return
        wrapper.setRemoteDescription(offer, SessionDescription.Type.OFFER)
        wrapper.createAnswer { sdp ->
            scope.launch {
                VpnLog.d(TAG, "Sending answer to $fromPeerId")
                signaling.sendAnswer(netId, fromPeerId, sdp)
            }
        }
    }

    private fun handleIncomingAnswer(fromPeerId: String, answer: String) {
        val wrapper = webRtcManager?.getConnection(fromPeerId)
        if (wrapper == null) {
            VpnLog.w(TAG, "No connection for $fromPeerId (answer)")
            return
        }
        wrapper.setRemoteDescription(answer, SessionDescription.Type.ANSWER)
    }

    private fun handleIncomingIceCandidate(fromPeerId: String, candidateStr: String) {
        val wrapper = webRtcManager?.getConnection(fromPeerId)
        if (wrapper == null) {
            scope.launch {
                delay(1500)
                val w = webRtcManager?.getConnection(fromPeerId)
                if (w != null) parseAndAddIce(w, candidateStr)
                else VpnLog.w(TAG, "Dropped ICE for $fromPeerId")
            }
            return
        }
        parseAndAddIce(wrapper, candidateStr)
    }

    private fun parseAndAddIce(wrapper: WebRtcManager.PeerConnectionWrapper, str: String) {
        try {
            val parts = str.split("|", limit = 3)
            if (parts.size == 3) wrapper.addIceCandidate(parts[0], parts[1].toInt(), parts[2])
        } catch (e: Exception) {
            VpnLog.e(TAG, "ICE parse error: ${e.message}")
        }
    }

    private fun createWebRtcListener() = object : WebRtcManager.Listener {
        override fun onIceCandidate(peerId: String, candidate: IceCandidate) {
            val str = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
            scope.launch {
                val netId = currentNetworkId ?: return@launch
                signaling.sendIceCandidate(netId, peerId, str)
            }
        }

        override fun onDataChannelMessage(peerId: String, data: ByteArray) {
            P2PVpnService.instance?.writePacket(data)
        }

        override fun onPeerConnected(peerId: String) {
            _connectionState.value = ConnectionState.CONNECTED
            _statusMessage.value = "P2P подключено!"
            VpnLog.success(TAG, "CONNECTED to $peerId")
            updatePeerStatus(peerId, true)
            if (!vpnStarted) startVpnService()
        }

        override fun onPeerDisconnected(peerId: String) {
            VpnLog.w(TAG, "Disconnected: $peerId")
            updatePeerStatus(peerId, false)
            connectingPeers.remove(peerId)
            if (!_peers.value.any { it.isConnected }) {
                _statusMessage.value = "Пиры отключены"
                _connectionState.value = ConnectionState.WAITING_FOR_PEERS
            }
        }

        override fun onDataChannelOpen(peerId: String) {
            VpnLog.success(TAG, "Tunnel OPEN: $peerId")
            _statusMessage.value = "Туннель активен!"
        }

        override fun onDataChannelClose(peerId: String) {
            VpnLog.w(TAG, "Tunnel closed: $peerId")
        }
    }

    private fun updatePeerStatus(peerId: String, connected: Boolean) {
        _peers.value = _peers.value.map {
            if (it.peerId == peerId) it.copy(isConnected = connected) else it
        }
    }

    private fun startVpnService() {
        val myIp = _virtualIp.value
        if (myIp.isEmpty()) return
        VpnLog.i(TAG, "Starting VPN: $myIp")
        val intent = Intent(context, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, myIp)
        }
        context.startForegroundService(intent)
        vpnStarted = true
        scope.launch {
            delay(500)
            P2PVpnService.instance?.onPacketReceived = { packet ->
                webRtcManager?.sendPacketToAll(packet)
            }
            VpnLog.success(TAG, "VPN linked to WebRTC")
        }
    }

    fun disconnect() {
        scope.launch {
            VpnLog.i(TAG, "Disconnecting...")
            currentNetworkId?.let {
                try { signaling.leaveNetwork(it) } catch (_: Exception) {}
            }
            context.startService(Intent(context, P2PVpnService::class.java).apply {
                action = P2PVpnService.ACTION_STOP
            })
            webRtcManager?.dispose()
            webRtcManager = null
            vpnStarted = false
            connectingPeers.clear()
            processedIceCandidates.clear()
            _connectionState.value = ConnectionState.DISCONNECTED
            _virtualIp.value = ""
            _networkId.value = ""
            _peers.value = emptyList()
            _statusMessage.value = "Отключено"
            currentNetworkId = null
            VpnLog.i(TAG, "Disconnected")
        }
    }

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
