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
    val isConnected: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    WAITING_FOR_PEERS,
    CONNECTED
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String = "",
    val message: String = ""
)

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    SUCCESS
}
