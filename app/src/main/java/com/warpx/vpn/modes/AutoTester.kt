package com.warpx.vpn.modes

import com.warpx.vpn.doh.DoH
import java.net.InetSocketAddress
import java.net.Socket

object AutoTester {
    val targets = listOf("rutracker.org", "youtube.com", "discord.com")

    fun run(): List<String> {
        val out = mutableListOf<String>()
        val strategies = DpiStrategy.presets()
        var best: DpiStrategy? = null
        var bestOk = -1
        out.add("Автотест стратегий:")
        for (s in strategies) {
            var ok = 0
            var total = 0L
            for (host in targets) {
                val ms = test(s, host)
                if (ms >= 0) {
                    ok++
                    total += ms
                }
            }
            val avg = if (ok > 0) "${total / ok}ms" else "-"
            out.add("${s.name}: $ok/${targets.size} достижимо, среднее $avg")
            if (ok > bestOk) {
                bestOk = ok
                best = s
            }
        }
        if (best != null) {
            Mode.activeStrategy = best
            out.add("Выбрана лучшая стратегия: ${best.name}")
        }
        return out
    }

    private fun test(s: DpiStrategy, host: String): Long {
        val ip = DoH.resolve(host, 1).firstOrNull() ?: return -1
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.connect(InetSocketAddress(ip, 443), 4000)
            sock.tcpNoDelay = true
            sock.soTimeout = 5000
            val start = System.currentTimeMillis()
            s.apply(sock.getOutputStream(), TlsHello.build(host))
            val buf = ByteArray(4096)
            val n = sock.getInputStream().read(buf)
            if (n > 0) System.currentTimeMillis() - start else -1
        } catch (e: Exception) {
            -1
        } finally {
            try { sock?.close() } catch (e: Exception) { }
        }
    }
}
