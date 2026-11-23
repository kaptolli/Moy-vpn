package com.warpx.vpn.core

import android.net.VpnService
import java.net.InetSocketAddress
import java.net.Socket

object Upstream {
    fun open(vpn: VpnService, ip: Int, port: Int, timeoutMs: Int = 10000): Socket {
        val s = Socket()
        vpn.protect(s)
        s.connect(InetSocketAddress(Pkt.inet(ip), port), timeoutMs)
        s.tcpNoDelay = true
        return s
    }
}
