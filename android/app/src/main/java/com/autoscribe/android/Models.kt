package com.autoscribe.android

/** One Windscribe server ("pop"), e.g. "Toronto - Comfort Zone" in Canada. */
data class Pop(
    val country: String,
    val countryCode: String,
    val continent: String,
    val name: String,
    val premium: Boolean = false,
) {
    val city: String get() = name.substringBefore(" - ").trim()
    val nick: String get() = if (" - " in name) name.substringAfter(" - ").trim() else ""
    val key: String get() = "$countryCode|$name"
    val label: String get() = if (nick.isEmpty()) city else "$city - $nick"
}

data class Route(val protocol: String, val port: Int) {
    val label: String get() = "${Protocols.displayName(protocol)} $port"
}

data class TestResult(
    val popKey: String,
    val label: String,
    val country: String,
    val protocol: String,
    val port: Int,
    val status: String,
    val duration: Double,
    val ping: Double?,
    val loss: Double?,
    val time: Long,
) {
    val ok: Boolean get() = status == STATUS_SUCCESS
    val route: Route get() = Route(protocol, port)
    val countryCode: String get() = popKey.substringBefore("|")

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILURE = "FAILURE"
        const val STATUS_ERROR = "ERROR"
    }
}

object Protocols {
    // Same order and defaults as the desktop engine.
    val DEFAULTS: LinkedHashMap<String, List<Int>> = linkedMapOf(
        "wireguard" to listOf(443, 80, 53, 123, 1194, 65142),
        "ikev2" to listOf(500),
        "udp" to listOf(443, 80, 53, 123, 1194, 54783),
        "tcp" to listOf(443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 1194),
        "stealth" to listOf(443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 8443),
        "wstunnel" to listOf(443),
    )

    private val NAMES = mapOf(
        "wireguard" to "WireGuard", "ikev2" to "IKEv2", "udp" to "UDP",
        "tcp" to "TCP", "stealth" to "Stealth", "wstunnel" to "WStunnel",
    )

    fun displayName(id: String): String = NAMES[id] ?: id
}
