package com.radminvpn.android.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Управляет WebRTC P2P соединением.
 * Создаёт DataChannel для передачи IP-пакетов.
 */
class WebRtcManager(
    private val context: Context,
    private val listener: WebRtcListener
) {

    companion object {
        private const val TAG = "WebRtcManager"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.services.mozilla.com:3478").createIceServer()
        )
    }

    interface WebRtcListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onDataChannelMessage(data: ByteArray)
        fun onConnectionStateChanged(state: PeerConnection.IceConnectionState)
        fun onDataChannelOpen()
        fun onDataChannelClose()
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    /**
     * Создать PeerConnection и DataChannel (для инициатора соединения)
     */
    fun createPeerConnection(isInitiator: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            createPeerConnectionObserver()
        )

        if (isInitiator) {
            // Создаём DataChannel для передачи пакетов
            val dcInit = DataChannel.Init().apply {
                ordered = false // Для скорости - неупорядоченная доставка
                maxRetransmits = 0 // Unreliable для VPN-трафика (UDP-like)
            }
            dataChannel = peerConnection?.createDataChannel("vpn-tunnel", dcInit)
            dataChannel?.registerObserver(createDataChannelObserver())
        }
    }

    /**
     * Создать SDP offer
     */
    fun createOffer(callback: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                callback(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
            }
        }, constraints)
    }

    /**
     * Создать SDP answer
     */
    fun createAnswer(callback: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                callback(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create answer failed: $error")
            }
        }, constraints)
    }

    /**
     * Установить remote SDP (offer или answer)
     */
    fun setRemoteDescription(sdp: String, type: SessionDescription.Type) {
        val sessionDescription = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sessionDescription)
    }

    /**
     * Добавить ICE candidate от удалённого пира
     */
    fun addIceCandidate(candidateJson: String) {
        try {
            // Формат: sdpMid|sdpMLineIndex|candidate
            val parts = candidateJson.split("|")
            if (parts.size == 3) {
                val candidate = IceCandidate(parts[0], parts[1].toInt(), parts[2])
                peerConnection?.addIceCandidate(candidate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate: ${e.message}")
        }
    }

    /**
     * Отправить IP-пакет через DataChannel
     */
    fun sendPacket(data: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false

        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        return channel.send(buffer)
    }

    /**
     * Закрыть соединение
     */
    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnection?.dispose()
        dataChannel = null
        peerConnection = null
    }

    fun dispose() {
        close()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val candidateStr = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                listener.onIceCandidate(candidate)
                Log.d(TAG, "ICE candidate: $candidateStr")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state: $state")
                listener.onConnectionStateChanged(state)
            }

            override fun onDataChannel(dc: DataChannel) {
                // Вызывается у принимающей стороны
                Log.d(TAG, "Remote DataChannel received")
                dataChannel = dc
                dc.registerObserver(createDataChannelObserver())
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}

            override fun onStateChange() {
                val state = dataChannel?.state()
                Log.d(TAG, "DataChannel state: $state")
                when (state) {
                    DataChannel.State.OPEN -> listener.onDataChannelOpen()
                    DataChannel.State.CLOSED -> listener.onDataChannelClose()
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                listener.onDataChannelMessage(data)
            }
        }
    }
}

/**
 * Адаптер для SdpObserver (реализует все методы пустыми)
 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
