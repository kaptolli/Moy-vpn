package com.warpx.vpn.doh

import java.util.concurrent.ConcurrentHashMap

object DnsCache {
    private val ipToHost = ConcurrentHashMap<String, MutableSet<String>>()
    private val hostToIp = ConcurrentHashMap<String, MutableList<String>>()

    fun put(host: String, ips: List<String>) {
        val h = host.lowercase().removeSuffix(".")
        for (ip in ips) {
            ipToHost.computeIfAbsent(ip) { mutableSetOf() }.add(h)
        }
        hostToIp[h] = ips.toMutableList()
    }

    fun hostsForIp(ip: String): Set<String> = ipToHost[ip] ?: emptySet()

    fun ipsForHost(host: String): List<String> =
        hostToIp[host.lowercase().removeSuffix(".")] ?: emptyList()
}
