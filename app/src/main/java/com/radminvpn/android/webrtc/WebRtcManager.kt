package com.radminvpn.android.webrtc

import android.content.Context
import com.radminvpn.android.util.VpnLog
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebRTC PeerConnection factory (singleton per app).
 * Creates per-peer connections via PeerConnectionWrapper.
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
    }

    interface Listener {
        fun onIceCandidate(peerId: String, candidate: IceCandidate)
        fun onDataChannelMessage(peerId: String, data: ByteArray)
        fun onPeerConnected(peerId: String)
        fun onPeerDisconnected(peerId: String)
        fun onDataChannelOpen(peerId: String)
        fun onDataChannelClose(peerId: String)
    }

    private var factory: PeerConnectionFactory? = null
    private val connections = ConcurrentHashMap<String, PeerConnectionWrapper>()
    private var listener: Listener? = null

    fun initialize(listener: Listener) {
        this.listener = listener
        VpnLog.i(TAG, "Initializing WebRTC...")

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        VpnLog.success(TAG, "WebRTC initialized successfully")
    }

    /**
     * Create a new peer connection for a specific remote peer.
     */
    fun createConnection(remotePeerId: String, isInitiator: Boolean): PeerConnectionWrapper? {
        val f = factory ?: run {
            VpnLog.e(TAG, "Factory not initialized!")
            return null
        }

        VpnLog.i(TAG, "Creating connection to peer: $remotePeerId (initiator=$isInitiator)")

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Better connectivity through relay if needed
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val wrapper = PeerConnectionWrapper(remotePeerId, isInitiator)
        val pc = f.createPeerConnection(rtcConfig, wrapper.observer)

        if (pc == null) {
            VpnLog.e(TAG, "Failed to create PeerConnection for $remotePeerId")
            return null
        }

        wrapper.peerConnection = pc

        if (isInitiator) {
            // Create DataChannel on initiator side
            val dcInit = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = 3
            }
            val dc = pc.createDataChannel("vpn-tunnel", dcInit)
            wrapper.setDataChannel(dc)
            VpnLog.d(TAG, "DataChannel created for peer $remotePeerId")
        }

        connections[remotePeerId] = wrapper
        return wrapper
    }

    fun getConnection(peerId: String): PeerConnectionWrapper? = connections[peerId]

    fun sendPacketToAll(data: ByteArray) {
        connections.values.forEach { wrapper ->
            wrapper.sendData(data)
        }
    }

    fun sendPacketToPeer(peerId: String, data: ByteArray): Boolean {
        return connections[peerId]?.sendData(data) ?: false
    }

    fun closeConnection(peerId: String) {
        connections.remove(peerId)?.close()
        VpnLog.i(TAG, "Closed connection to $peerId")
    }

    fun dispose() {
        connections.values.forEach { it.close() }
        connections.clear()
        factory?.dispose()
        factory = null
        VpnLog.i(TAG, "WebRtcManager disposed")
    }

    /**
     * Wrapper around a single PeerConnection for one remote peer.
     */
    inner class PeerConnectionWrapper(
        val remotePeerId: String,
        private val isInitiator: Boolean
    ) {
        var peerConnection: PeerConnection? = null
        private var dataChannel: DataChannel? = null
        private var isChannelOpen = false

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                VpnLog.d(TAG, "[$remotePeerId] ICE candidate generated: ${candidate.sdp.take(50)}...")
                listener?.onIceCandidate(remotePeerId, candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                VpnLog.i(TAG, "[$remotePeerId] ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        VpnLog.success(TAG, "[$remotePeerId] P2P connection established!")
                        listener?.onPeerConnected(remotePeerId)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        VpnLog.w(TAG, "[$remotePeerId] Peer disconnected")
                        listener?.onPeerDisconnected(remotePeerId)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        VpnLog.e(TAG, "[$remotePeerId] Connection FAILED")
                        listener?.onPeerDisconnected(remotePeerId)
                    }
                    else -> {}
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                // Called on the answerer side when remote opens DataChannel
                VpnLog.i(TAG, "[$remotePeerId] Remote DataChannel received: ${dc.label()}")
                setDataChannel(dc)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                VpnLog.d(TAG, "[$remotePeerId] Signaling state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                VpnLog.d(TAG, "[$remotePeerId] ICE gathering: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

        fun setDataChannel(dc: DataChannel) {
            dataChannel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}

                override fun onStateChange() {
                    val state = dc.state()
                    VpnLog.i(TAG, "[$remotePeerId] DataChannel state: $state")
                    when (state) {
                        DataChannel.State.OPEN -> {
                            isChannelOpen = true
                            listener?.onDataChannelOpen(remotePeerId)
                        }
                        DataChannel.State.CLOSED -> {
                            isChannelOpen = false
                            listener?.onDataChannelClose(remotePeerId)
                        }
                        else -> {}
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    listener?.onDataChannelMessage(remotePeerId, data)
                }
            })
        }

        fun createOffer(callback: (String) -> Unit) {
            val constraints = MediaConstraints()
            peerConnection?.createOffer(object : SdpObserverAdapter("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    VpnLog.d(TAG, "[$remotePeerId] Offer created, setting local description...")
                    peerConnection?.setLocalDescription(
                        SdpObserverAdapter("setLocalDesc-offer"),
                        sdp
                    )
                    callback(sdp.description)
                }
            }, constraints)
        }

        fun createAnswer(callback: (String) -> Unit) {
            val constraints = MediaConstraints()
            peerConnection?.createAnswer(object : SdpObserverAdapter("createAnswer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    VpnLog.d(TAG, "[$remotePeerId] Answer created, setting local description...")
                    peerConnection?.setLocalDescription(
                        SdpObserverAdapter("setLocalDesc-answer"),
                        sdp
                    )
                    callback(sdp.description)
                }
            }, constraints)
        }

        fun setRemoteDescription(sdp: String, type: SessionDescription.Type) {
            VpnLog.d(TAG, "[$remotePeerId] Setting remote description (${type.name})...")
            val sessionDescription = SessionDescription(type, sdp)
            peerConnection?.setRemoteDescription(
                SdpObserverAdapter("setRemoteDesc-${type.name}"),
                sessionDescription
            )
        }

        fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            val result = peerConnection?.addIceCandidate(iceCandidate)
            VpnLog.d(TAG, "[$remotePeerId] Added ICE candidate: success=$result")
        }

        fun sendData(data: ByteArray): Boolean {
            if (!isChannelOpen) return false
            val channel = dataChannel ?: return false
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
            return channel.send(buffer)
        }

        fun close() {
            dataChannel?.close()
            peerConnection?.close()
            peerConnection?.dispose()
            dataChannel = null
            peerConnection = null
            isChannelOpen = false
        }
    }
}

/**
 * SDP observer adapter with logging
 */
open class SdpObserverAdapter(private val label: String = "") : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {
        VpnLog.d("SDP", "[$label] onCreateSuccess")
    }
    override fun onSetSuccess() {
        VpnLog.d("SDP", "[$label] onSetSuccess")
    }
    override fun onCreateFailure(error: String) {
        VpnLog.e("SDP", "[$label] onCreateFailure: $error")
    }
    override fun onSetFailure(error: String) {
        VpnLog.e("SDP", "[$label] onSetFailure: $error")
    }
}
