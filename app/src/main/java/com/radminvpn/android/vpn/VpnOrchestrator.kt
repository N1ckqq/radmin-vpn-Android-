package com.radminvpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.radminvpn.android.model.ConnectionState
import com.radminvpn.android.model.PeerInfo
import com.radminvpn.android.signaling.FirebaseSignaling
import com.radminvpn.android.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/**
 * Оркестратор — связывает Firebase Signaling, WebRTC и VPN сервис.
 * Управляет жизненным циклом P2P VPN соединения.
 */
class VpnOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "VpnOrchestrator"
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

    /**
     * Создать новую сеть (я — хост)
     */
    fun createNetwork() {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Создание сети..."

                val netId = signaling.createNetwork()
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = "10.0.0.1"

                _statusMessage.value = "Сеть создана: $netId"
                _connectionState.value = ConnectionState.CONNECTED

                // Слушаем новых пиров
                listenForNewPeers(netId)

            } catch (e: Exception) {
                Log.e(TAG, "Create network failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Присоединиться к существующей сети
     */
    fun joinNetwork(netId: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _statusMessage.value = "Подключение к сети $netId..."

                val myIp = signaling.joinNetwork(netId)
                currentNetworkId = netId
                _networkId.value = netId
                _virtualIp.value = myIp

                _statusMessage.value = "Подключено! IP: $myIp"

                // Инициируем WebRTC соединения с существующими пирами
                listenForNewPeers(netId)
                initWebRtcWithExistingPeers(netId)

            } catch (e: Exception) {
                Log.e(TAG, "Join network failed: ${e.message}")
                _statusMessage.value = "Ошибка: ${e.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Слушаем новых пиров и устанавливаем с ними P2P соединение
     */
    private fun listenForNewPeers(netId: String) {
        scope.launch {
            signaling.listenForPeers(netId).collect { peerInfo ->
                Log.d(TAG, "New peer: ${peerInfo.peerId} (${peerInfo.virtualIp})")
                val currentPeers = _peers.value.toMutableList()
                if (currentPeers.none { it.peerId == peerInfo.peerId }) {
                    currentPeers.add(peerInfo)
                    _peers.value = currentPeers
                }
            }
        }

        // Слушаем входящие offers (когда к нам подключаются)
        scope.launch {
            signaling.listenForOffers(netId).collect { (fromPeerId, offer) ->
                Log.d(TAG, "Received offer from $fromPeerId")
                handleIncomingOffer(netId, fromPeerId, offer)
            }
        }

        // Слушаем ответы на наши offers
        scope.launch {
            signaling.listenForAnswers(netId).collect { (targetPeerId, answer) ->
                Log.d(TAG, "Received answer from $targetPeerId")
                handleIncomingAnswer(targetPeerId, answer)
            }
        }
    }

    /**
     * Подключаемся к существующим пирам в сети
     */
    private suspend fun initWebRtcWithExistingPeers(netId: String) {
        val existingPeers = _peers.value
        for (peer in existingPeers) {
            initiateConnectionToPeer(netId, peer.peerId)
        }
    }

    /**
     * Инициировать WebRTC соединение с конкретным пиром
     */
    private fun initiateConnectionToPeer(netId: String, targetPeerId: String) {
        val rtcManager = createWebRtcManager(netId, targetPeerId)
        rtcManager.createPeerConnection(isInitiator = true)

        rtcManager.createOffer { sdpOffer ->
            scope.launch {
                signaling.sendOffer(netId, targetPeerId, sdpOffer)
            }
        }
    }

    /**
     * Обработать входящий offer
     */
    private fun handleIncomingOffer(netId: String, fromPeerId: String, offer: String) {
        val rtcManager = createWebRtcManager(netId, fromPeerId)
        rtcManager.createPeerConnection(isInitiator = false)

        rtcManager.setRemoteDescription(offer, org.webrtc.SessionDescription.Type.OFFER)

        rtcManager.createAnswer { sdpAnswer ->
            scope.launch {
                signaling.sendAnswer(netId, fromPeerId, sdpAnswer)
            }
        }

        // Слушаем ICE кандидатов от этого пира
        scope.launch {
            signaling.listenForIceCandidates(netId, fromPeerId).collect { candidate ->
                rtcManager.addIceCandidate(candidate)
            }
        }
    }

    /**
     * Обработать ответ на наш offer
     */
    private fun handleIncomingAnswer(targetPeerId: String, answer: String) {
        webRtcManager?.setRemoteDescription(answer, org.webrtc.SessionDescription.Type.ANSWER)
    }

    /**
     * Создать WebRTC менеджер с привязкой к VPN
     */
    private fun createWebRtcManager(netId: String, remotePeerId: String): WebRtcManager {
        val manager = WebRtcManager(context, object : WebRtcManager.WebRtcListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                val candidateStr = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                scope.launch {
                    signaling.sendIceCandidate(netId, remotePeerId, candidateStr)
                }
            }

            override fun onDataChannelMessage(data: ByteArray) {
                // Пакет получен от удалённого пира → записать в TUN
                P2PVpnService.instance?.writePacket(data)
            }

            override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        _connectionState.value = ConnectionState.CONNECTED
                        _statusMessage.value = "P2P соединение установлено!"
                        startVpnService()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        _statusMessage.value = "Соединение потеряно"
                    }
                    else -> {}
                }
            }

            override fun onDataChannelOpen() {
                Log.d(TAG, "DataChannel OPEN — туннель активен")
                _statusMessage.value = "Туннель активен!"
            }

            override fun onDataChannelClose() {
                Log.d(TAG, "DataChannel CLOSED")
            }
        })

        webRtcManager = manager
        return manager
    }

    /**
     * Запустить VPN сервис
     */
    private fun startVpnService() {
        val myIp = _virtualIp.value
        val peerIp = _peers.value.firstOrNull()?.virtualIp ?: "10.0.0.2"

        val intent = Intent(context, P2PVpnService::class.java).apply {
            action = P2PVpnService.ACTION_START
            putExtra(P2PVpnService.EXTRA_VIRTUAL_IP, myIp)
            putExtra(P2PVpnService.EXTRA_PEER_VIRTUAL_IP, peerIp)
        }
        context.startForegroundService(intent)

        // Привязать чтение из TUN к отправке через WebRTC
        P2PVpnService.instance?.onPacketReceived = { packet ->
            webRtcManager?.sendPacket(packet)
        }
    }

    /**
     * Отключиться от сети
     */
    fun disconnect() {
        scope.launch {
            currentNetworkId?.let { signaling.leaveNetwork(it) }

            val intent = Intent(context, P2PVpnService::class.java).apply {
                action = P2PVpnService.ACTION_STOP
            }
            context.startService(intent)

            webRtcManager?.dispose()
            webRtcManager = null

            _connectionState.value = ConnectionState.DISCONNECTED
            _virtualIp.value = ""
            _networkId.value = ""
            _peers.value = emptyList()
            _statusMessage.value = "Отключено"
            currentNetworkId = null
        }
    }

    /**
     * Проверить, готов ли VPN (есть ли разрешение)
     */
    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
