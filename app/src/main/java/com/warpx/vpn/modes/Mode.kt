package com.warpx.vpn.modes

import com.warpx.vpn.core.Pkt
import java.util.concurrent.ConcurrentHashMap

enum class ModeType { WARP, DPI, WHITELIST, BLOCK }

object Mode {
    @Volatile
    var current: ModeType = ModeType.DPI

    @Volatile
    var desyncEnabled: Boolean = true

    @Volatile
    var activeStrategy: DpiStrategy? = DpiStrategy.presets().first()

    // Режим 4 (блокировка / flowseal-подобный контроль)
    @Volatile
    var blockAll: Boolean = false
    val denyHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val allowHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val resolverIps = setOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112"
    )

    fun shouldBlock(ip: Int, host: String?): Boolean {
        if (current != ModeType.BLOCK) return false
        val ipStr = Pkt.inet(ip).hostAddress ?: ""
        if (ipStr in resolverIps) return false
        val h = host?.lowercase() ?: ""
        for (d in denyHosts) {
            if (h.isNotEmpty() && h.contains(d)) return true
            if (d == ipStr) return true
        }
        if (blockAll && allowHosts.isNotEmpty()) {
            val allowed = allowHosts.any { (h.isNotEmpty() && h.contains(it)) || it == ipStr }
            return !allowed
        }
        return false
    }

    fun shouldDesync(): Boolean =
        desyncEnabled && (current == ModeType.DPI || current == ModeType.WHITELIST)
}
