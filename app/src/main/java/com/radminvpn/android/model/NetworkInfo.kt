package com.radminvpn.android.model

data class NetworkInfo(
    val networkId: String = "",
    val creatorId: String = "",
    val createdAt: Long = 0,
    val peers: Map<String, PeerInfo> = emptyMap()
)

data class PeerInfo(
    val peerId: String = "",
    val virtualIp: String = "",
    val joinedAt: Long = 0,
    val offer: String = "",
    val answer: String = "",
    val iceCandidates: List<String> = emptyList()
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
