package com.radminvpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
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
 * Simplified orchestrator. The flow is:
 * 1. Create/join network → get list of peers
 * 2. For each peer: deterministic side sends offer
 * 3. Other side receives offer via targeted ValueEventListener
 * 4. ICE candidates exchanged via targeted ChildEventListener
 * 5. On DataChannel open → start VPN service
 */
class VpnOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Orch"
    }

    private val signaling = FirebaseSignaling()
    private var webRtc: WebRtcManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _state

    private val _virtualIp = MutableStateFlow("")
    val virtualIp: StateFlow<String> = _virtualIp

    private val _networkId = MutableStateFlow("")
    val networkId: StateFlow<String> = _networkId

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers

    private val _status = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _status

    private var currentNetId: String? = null
    private var vpnStarted = false
    private val connectedPeers = mutableSetOf<String>()

    fun createNetwork() {
        scope.launch {
            try {
                _state.value = ConnectionState.CONNECTING
                _status.value = "Создание сети..."

                initWebRtc()
                val netId = signaling.createNetwork()
                currentNetId = netId
                _networkId.value = netId
                _virtualIp.value = "10.0.0.1"
                _state.value = ConnectionState.WAITING_FOR_PEERS
                _status.value = "Сеть: $netId"

                VpnLog.i(TAG, "Waiting for peers...")

                // When peer joins → connect
                scope.launch {
                    signaling.listenForPeers(netId).collect { peer ->
                        addPeerAndConnect(netId, peer)
                    }
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Create error: ${e.message}")
                _state.value = ConnectionState.DISCONNECTED
                _status.value = "Ошибка: ${e.message}"
            }
        }
    }

    fun joinNetwork(netId: String) {
        scope.launch {
            try {
                _state.value = ConnectionState.CONNECTING
                _status.value = "Подключение..."

                initWebRtc()
                val ip = signaling.joinNetwork(netId)
                currentNetId = netId
                _networkId.value = netId
                _virtualIp.value = ip
                _state.value = ConnectionState.WAITING_FOR_PEERS
                _status.value = "IP: $ip. Подключение к пирам..."

                // Listen for peers (will get existing ones immediately via onChildAdded)
                scope.launch {
                    signaling.listenForPeers(netId).collect { peer ->
                        addPeerAndConnect(netId, peer)
                    }
                }
            } catch (e: Exception) {
                VpnLog.e(TAG, "Join error: ${e.message}")
                _state.value = ConnectionState.DISCONNECTED
                _status.value = "Ошибка: ${e.message}"
            }
        }
    }

    private fun addPeerAndConnect(netId: String, peer: PeerInfo) {
        if (_peers.value.any { it.peerId == peer.peerId }) return
        _peers.value = _peers.value + peer
        connectToPeer(netId, peer.peerId)
    }

    /**
     * Connect to a single peer. Deterministic: smaller ID = offerer.
     */
    private fun connectToPeer(netId: String, remotePeerId: String) {
        val myId = signaling.localPeerId
        val iAmOfferer = myId < remotePeerId

        VpnLog.i(TAG, "Connect to $remotePeerId (I ${if (iAmOfferer) "offer" else "answer"})")

        val rtc = webRtc ?: return
        val conn = rtc.createConnection(remotePeerId, isInitiator = iAmOfferer) ?: return

        // Start listening for ICE from remote (do this FIRST)
        signaling.listenForIceCandidates(netId, remotePeerId) { candidateStr ->
            parseAndAddIce(conn, candidateStr)
        }

        if (iAmOfferer) {
            // I create offer and send it
            conn.createOffer { sdp ->
                scope.launch {
                    signaling.sendOffer(netId, remotePeerId, sdp)
                }
            }
            // Listen for answer
            signaling.listenForAnswer(netId, remotePeerId) { answerSdp ->
                conn.setRemoteDescription(answerSdp, SessionDescription.Type.ANSWER)
            }
        } else {
            // Listen for offer from remote
            signaling.listenForOffer(netId, remotePeerId) { offerSdp ->
                conn.setRemoteDescription(offerSdp, SessionDescription.Type.OFFER)
                conn.createAnswer { sdp ->
                    scope.launch {
                        signaling.sendAnswer(netId, remotePeerId, sdp)
                    }
                }
            }
        }
    }

    private fun parseAndAddIce(conn: WebRtcManager.PeerConnectionWrapper, str: String) {
        try {
            val parts = str.split("|", limit = 3)
            if (parts.size == 3) {
                conn.addIceCandidate(parts[0], parts[1].toInt(), parts[2])
            }
        } catch (e: Exception) {
            VpnLog.e(TAG, "ICE parse: ${e.message}")
        }
    }

    private fun initWebRtc() {
        if (webRtc != null) return
        val mgr = WebRtcManager(context)
        mgr.initialize(object : WebRtcManager.Listener {
            override fun onIceCandidate(peerId: String, candidate: IceCandidate) {
                val str = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                scope.launch {
                    val netId = currentNetId ?: return@launch
                    signaling.sendIceCandidate(netId, peerId, str)
                }
            }

            override fun onDataChannelMessage(peerId: String, data: ByteArray) {
                P2PVpnService.instance?.writePacket(data)
            }

            override fun onPeerConnected(peerId: String) {
                connectedPeers.add(peerId)
                _state.value = ConnectionState.CONNECTED
                _status.value = "Подключено!"
                updatePeer(peerId, true)
                VpnLog.success(TAG, "P2P CONNECTED: $peerId")
                if (!vpnStarted) startVpn()
            }

            override fun onPeerDisconnected(peerId: String) {
                connectedPeers.remove(peerId)
                updatePeer(peerId, false)
                VpnLog.w(TAG, "Disconnected: $peerId")
                if (connectedPeers.isEmpty()) {
                    _state.value = ConnectionState.WAITING_FOR_PEERS
                    _status.value = "Пир отключился"
                }
            }

            override fun onDataChannelOpen(peerId: String) {
                VpnLog.success(TAG, "TUNNEL OPEN: $peerId")
                _status.value = "Туннель активен!"
            }

            override fun onDataChannelClose(peerId: String) {
                VpnLog.w(TAG, "Tunnel closed: $peerId")
            }
        })
        webRtc = mgr
        VpnLog.i(TAG, "WebRTC initialized")
    }

    private fun updatePeer(peerId: String, connected: Boolean) {
        _peers.value = _peers.value.map {
            if (it.peerId == peerId) it.copy(isConnected = connected) else it
        }
    }

    private fun startVpn() {
        val ip = _virtualIp.value
        if (ip.isEmpty()) return
        VpnLog.i(TAG, "Starting VPN: $ip")
        val intent = Intent(context, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, ip)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        vpnStarted = true
        scope.launch {
            delay(300)
            P2PVpnService.instance?.onPacketReceived = { pkt ->
                webRtc?.sendPacketToAll(pkt)
            }
            VpnLog.success(TAG, "VPN ↔ WebRTC linked")
        }
    }

    fun disconnect() {
        scope.launch {
            VpnLog.i(TAG, "Disconnecting...")
            signaling.removeAllListeners()
            currentNetId?.let { try { signaling.leaveNetwork(it) } catch (_: Exception) {} }
            context.startService(Intent(context, P2PVpnService::class.java).apply {
                action = P2PVpnService.ACTION_STOP
            })
            webRtc?.dispose()
            webRtc = null
            vpnStarted = false
            connectedPeers.clear()
            currentNetId = null
            _state.value = ConnectionState.DISCONNECTED
            _virtualIp.value = ""
            _networkId.value = ""
            _peers.value = emptyList()
            _status.value = "Отключено"
            VpnLog.i(TAG, "Done")
        }
    }

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
