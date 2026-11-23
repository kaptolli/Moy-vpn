package com.warpx.vpn.core

import android.net.VpnService
import com.warpx.vpn.doh.DoH
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class UdpForwarder(private val vpn: VpnService, private val out: (ByteArray) -> Unit) {

    private data class Key(val ip: Int, val port: Int)

    private val map = ConcurrentHashMap<Key, DatagramSocket>()
    private val ipId = AtomicInteger(0)

    fun handle(pkt: ByteArray, len: Int) {
        if (len < 28) return
        val srcIp = Pkt.bytesToInt(pkt, 12)
        val dstIp = Pkt.bytesToInt(pkt, 16)
        val srcPort = ((pkt[20].toInt() and 0xff) shl 8) or (pkt[21].toInt() and 0xff)
        val dstPort = ((pkt[22].toInt() and 0xff) shl 8) or (pkt[23].toInt() and 0xff)
        val payload = pkt.copyOfRange(28, len)

        if (dstPort == 53) {
            handleDns(srcIp, srcPort, dstIp, payload)
            return
        }

        val key = Key(srcIp, srcPort)
        var sock = map[key]
        if (sock == null) {
            sock = DatagramSocket()
            vpn.protect(sock)
            map[key] = sock
            startReader(key, sock, srcIp, srcPort)
        }
        try {
            sock.send(DatagramPacket(payload, payload.size, Pkt.inet(dstIp), dstPort))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startReader(key: Key, sock: DatagramSocket, clientIp: Int, clientPort: Int) {
        Thread {
            val buf = ByteArray(32768)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (e: Exception) {
                    break
                }
                val data = p.data.copyOfRange(0, p.length)
                val serverIp = Pkt.bytesToInt(p.address.address, 0)
                sendUdp(serverIp, p.port, clientIp, clientPort, data)
            }
            map.remove(key)
            try { sock.close() } catch (e: Exception) { }
        }.also { it.name = "udp-read" }.start()
    }

    private fun handleDns(clientIp: Int, clientPort: Int, dnsServerIp: Int, query: ByteArray) {
        val q = DoH.parseQuery(query) ?: return
        Thread {
            val type = q.qtype
            val answers = DoH.resolve(q.domain, type).mapNotNull { ipStr ->
                try { InetAddress.getByName(ipStr) } catch (e: Exception) { null }
            }.filter { ip ->
                when (type) {
                    1 -> ip.address.size == 4
                    28 -> ip.address.size == 16
                    else -> false
                }
            }
            val resp = DoH.buildResponse(q, answers)
            sendUdp(dnsServerIp, 53, clientIp, clientPort, resp)
        }.also { it.name = "dns-resolve" }.start()
    }

    private fun sendUdp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray) {
        val udp = ByteBuffer.allocate(8 + data.size).order(ByteOrder.BIG_ENDIAN)
        udp.putShort(srcPort.toShort())
        udp.putShort(dstPort.toShort())
        udp.putShort((8 + data.size).toShort())
        udp.putShort(0) // checksum = 0
        udp.put(data)
        out(Pkt.buildIpv4(srcIp, dstIp, Pkt.UDP, udp.array(), ipId.incrementAndGet() and 0xffff))
    }

    fun closeAll() {
        for ((k, s) in map) {
            try { s.close() } catch (e: Exception) { }
            map.remove(k)
        }
    }
}
