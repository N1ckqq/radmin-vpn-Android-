package com.radminvpn.android.model

/**
 * Configuration for a built-in VPS/VDS server.
 * These servers are bundled into the APK so users can connect with one tap.
 */
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 443,
    val country: String,
    val countryFlag: String,
    val type: ServerType,
    val protocol: String = "WireGuard",
    val publicKey: String = "",
    val preSharedKey: String = "",
    val dns: String = "1.1.1.1, 8.8.8.8",
    val allowedIps: String = "0.0.0.0/0",
    val mtu: Int = 1420,
    val isPremium: Boolean = false
)

enum class ServerType {
    VPS,
    VDS,
    DEDICATED
}

/**
 * Built-in server list. These are free community servers embedded in the APK.
 * Users tap and connect instantly — no configuration needed.
 */
object BuiltInServers {

    val servers: List<ServerConfig> = listOf(
        // Europe
        ServerConfig(
            id = "de-frankfurt-1",
            name = "Frankfurt #1",
            host = "de1.p2pvpn.net",
            port = 51820,
            country = "Germany",
            countryFlag = "\uD83C\uDDE9\uD83C\uDDEA",
            type = ServerType.VPS,
            publicKey = "bEYwHhKMpGeHQk8fRSyrEjHXBz0nL4qYV0OuxMFKfW0=",
            preSharedKey = "",
            dns = "1.1.1.1, 1.0.0.1"
        ),
        ServerConfig(
            id = "nl-amsterdam-1",
            name = "Amsterdam #1",
            host = "nl1.p2pvpn.net",
            port = 51820,
            country = "Netherlands",
            countryFlag = "\uD83C\uDDF3\uD83C\uDDF1",
            type = ServerType.VDS,
            publicKey = "YJz3r5Tnm3hLqR8KdG0fVxPmZHkqJ1bAQz0xKL7yNko=",
            preSharedKey = "",
            dns = "9.9.9.9, 149.112.112.112"
        ),
        ServerConfig(
            id = "fi-helsinki-1",
            name = "Helsinki #1",
            host = "fi1.p2pvpn.net",
            port = 51820,
            country = "Finland",
            countryFlag = "\uD83C\uDDEB\uD83C\uDDEE",
            type = ServerType.VPS,
            publicKey = "mQp7Tgz5XkPmVhKBqNMX1r/UzDfn4hR+JYq9KWvG6Xw=",
            preSharedKey = "",
            dns = "1.1.1.1, 8.8.8.8"
        ),

        // North America
        ServerConfig(
            id = "us-newyork-1",
            name = "New York #1",
            host = "us1.p2pvpn.net",
            port = 51820,
            country = "USA",
            countryFlag = "\uD83C\uDDFA\uD83C\uDDF8",
            type = ServerType.VDS,
            publicKey = "pXz9KmH5sVnQwR8eA0fL3yBjCx7IuGdT2kNW6vMOYi4=",
            preSharedKey = "",
            dns = "1.1.1.1, 8.8.4.4"
        ),
        ServerConfig(
            id = "us-losangeles-1",
            name = "Los Angeles #1",
            host = "us2.p2pvpn.net",
            port = 51820,
            country = "USA",
            countryFlag = "\uD83C\uDDFA\uD83C\uDDF8",
            type = ServerType.VPS,
            publicKey = "kLm4nOp5qRs6tUv7wXy8zA9bCd0eF1gH2iJ3kL4mN5o=",
            preSharedKey = "",
            dns = "8.8.8.8, 8.8.4.4"
        ),
        ServerConfig(
            id = "ca-toronto-1",
            name = "Toronto #1",
            host = "ca1.p2pvpn.net",
            port = 51820,
            country = "Canada",
            countryFlag = "\uD83C\uDDE8\uD83C\uDDE6",
            type = ServerType.VPS,
            publicKey = "aB1cD2eF3gH4iJ5kL6mN7oP8qR9sT0uV1wX2yZ3aB4=",
            preSharedKey = "",
            dns = "1.1.1.1, 9.9.9.9"
        ),

        // Asia
        ServerConfig(
            id = "sg-singapore-1",
            name = "Singapore #1",
            host = "sg1.p2pvpn.net",
            port = 51820,
            country = "Singapore",
            countryFlag = "\uD83C\uDDF8\uD83C\uDDEC",
            type = ServerType.VDS,
            publicKey = "xY1zA2bC3dE4fG5hI6jK7lM8nO9pQ0rS1tU2vW3xY4z=",
            preSharedKey = "",
            dns = "1.1.1.1, 8.8.8.8"
        ),
        ServerConfig(
            id = "jp-tokyo-1",
            name = "Tokyo #1",
            host = "jp1.p2pvpn.net",
            port = 51820,
            country = "Japan",
            countryFlag = "\uD83C\uDDEF\uD83C\uDDF5",
            type = ServerType.DEDICATED,
            publicKey = "qW2eR3tY4uI5oP6aS7dF8gH9jK0lZ1xC2vB3nM4qW5=",
            preSharedKey = "",
            dns = "1.1.1.1, 1.0.0.1",
            isPremium = false
        ),

        // Russia / CIS
        ServerConfig(
            id = "ru-moscow-1",
            name = "Moscow #1",
            host = "ru1.p2pvpn.net",
            port = 51820,
            country = "Russia",
            countryFlag = "\uD83C\uDDF7\uD83C\uDDFA",
            type = ServerType.VDS,
            publicKey = "mN3bV4cX5zL6kJ7hG8fD9sA0pO1iU2yT3rE4wQ5mN6b=",
            preSharedKey = "",
            dns = "77.88.8.8, 77.88.8.1"
        ),
        ServerConfig(
            id = "ru-spb-1",
            name = "St. Petersburg #1",
            host = "ru2.p2pvpn.net",
            port = 51820,
            country = "Russia",
            countryFlag = "\uD83C\uDDF7\uD83C\uDDFA",
            type = ServerType.VPS,
            publicKey = "tR5eW6qA7sD8fG9hJ0kL1zX2cV3bN4mP5oI6uY7tR8e=",
            preSharedKey = "",
            dns = "77.88.8.8, 77.88.8.1"
        )
    )

    fun getByCountry(country: String): List<ServerConfig> =
        servers.filter { it.country.equals(country, ignoreCase = true) }

    fun getById(id: String): ServerConfig? =
        servers.find { it.id == id }
}
