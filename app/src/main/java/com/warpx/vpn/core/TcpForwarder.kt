package com.warpx.vpn.core

import android.net.VpnService
import com.warpx.vpn.doh.DnsCache
import com.warpx.vpn.modes.Mode
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TcpForwarder(private val vpn: VpnService, private val out: (ByteArray) -> Unit) {

    companion object {
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val ACK = 0x10
    }

    private data class Key(val sip: Int, val dip: Int, val sp: Int, val dp: Int)

    private class Conn(val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int) {
        lateinit var socket: Socket
        lateinit var input: InputStream
        lateinit var output: OutputStream
        var clientSeq: Int = 0
        var clientAck: Int = 0
        var firstWrite = true
        var finSent = false

        @Volatile
        var dead = false
    }

    private val conns = ConcurrentHashMap<Key, Conn>()
    private val ipId = AtomicInteger(0)

    fun handle(pkt: ByteArray, len: Int) {
        if (len < 40) return
        if ((pkt[9].toInt() and 0xff) != Pkt.TCP) return
        val ihl = (pkt[0].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 20) return
        val srcIp = Pkt.bytesToInt(pkt, 12)
        val dstIp = Pkt.bytesToInt(pkt, 16)
        val o = ihl
        val srcPort = ((pkt[o].toInt() and 0xff) shl 8) or (pkt[o + 1].toInt() and 0xff)
        val dstPort = ((pkt[o + 2].toInt() and 0xff) shl 8) or (pkt[o + 3].toInt() and 0xff)
        val seq = Pkt.bytesToInt(pkt, o + 4)
        val dataOffset = ((pkt[o + 12].toInt() and 0xf0) ushr 4) * 4
        val flags = pkt[o + 13].toInt() and 0xff
        val payloadStart = o + dataOffset
        val payloadLen = len - payloadStart
        val key = Key(srcIp, dstIp, srcPort, dstPort)

        val isSyn = flags and SYN != 0
        val isAck = flags and ACK != 0
        val isFin = flags and FIN != 0
        val isRst = flags and RST != 0

        if (isSyn && !isAck) {
            val c = Conn(srcIp, dstIp, srcPort, dstPort)
            val host = DnsCache.hostsForIp(Pkt.inet(dstIp).hostAddress ?: "").firstOrNull()
            if (Mode.shouldBlock(dstIp, host)) {
                sendRaw(c, RST or ACK, 0, seq + 1, ByteArray(0), ByteArray(0))
                return
            }
            conns[key] = c
            try {
                c.socket = Upstream.open(vpn, dstIp, dstPort)
                c.input = c.socket.getInputStream()
                c.output = c.socket.getOutputStream()
            } catch (e: Exception) {
                conns.remove(key)
                sendRaw(c, RST or ACK, 0, seq + 1, ByteArray(0), ByteArray(0))
                return
            }
            c.clientSeq = (System.nanoTime().toInt() xor (dstPort shl 16)) and 0x7fffffff
            c.clientAck = seq + 1
            val mss = byteArrayOf(2, 4, 5, 0xB4.toByte()) // MSS 1460
            sendUnlocked(c, SYN or ACK, mss, ByteArray(0))
            startReader(c, key)
            return
        }

        if (isRst) {
            conns[key]?.let { close(it, key) }
            return
        }

        if (isAck) {
            val c = conns[key] ?: return
            val payload = if (payloadLen > 0) pkt.copyOfRange(payloadStart, len) else null
            synchronized(c) {
                if (payload != null && payload.isNotEmpty()) {
                    c.clientAck += payload.size
                    try {
                        if (c.firstWrite) {
                            c.firstWrite = false
                            val s = Mode.activeStrategy
                            if (Mode.shouldDesync() && s != null) {
                                s.apply(c.output, payload)
                            } else {
                                c.output.write(payload)
                            }
                        } else {
                            c.output.write(payload)
                        }
                    } catch (e: Exception) {
                        close(c, key)
                        return
                    }
                    sendUnlocked(c, ACK, ByteArray(0), ByteArray(0))
                }
                if (isFin) {
                    c.clientAck += 1
                    try { c.socket.shutdownOutput() } catch (e: Exception) { }
                    sendUnlocked(c, ACK, ByteArray(0), ByteArray(0))
                    if (!c.finSent) {
                        c.finSent = true
                        sendUnlocked(c, FIN or ACK, ByteArray(0), ByteArray(0))
                    }
                }
            }
        }
    }

    private fun startReader(c: Conn, key: Key) {
        Thread {
            val buf = ByteArray(32768)
            try {
                while (!c.dead) {
                    val n = c.input.read(buf)
                    if (n < 0) break
                    synchronized(c) {
                        sendUnlocked(c, ACK, ByteArray(0), buf.copyOfRange(0, n))
                    }
                }
                if (!c.dead) {
                    synchronized(c) {
                        if (!c.finSent) {
                            c.finSent = true
                            sendUnlocked(c, FIN or ACK, ByteArray(0), ByteArray(0))
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                close(c, key)
            }
        }.also { it.name = "tcp-read" }.start()
    }

    // Вызывающий держит lock на c.
    private fun sendUnlocked(c: Conn, flags: Int, options: ByteArray, payload: ByteArray) {
        val tcp = Pkt.buildTcp(c.dstPort, c.srcPort, c.clientSeq, c.clientAck, flags, 65535, options, payload, c.dstIp, c.srcIp)
        out(Pkt.buildIpv4(c.dstIp, c.srcIp, Pkt.TCP, tcp, ipId.incrementAndGet() and 0xffff))
        c.clientSeq += payload.size
        if (flags and SYN != 0) c.clientSeq++
        if (flags and FIN != 0) c.clientSeq++
    }

    private fun sendRaw(c: Conn, flags: Int, seq: Int, ackNum: Int, options: ByteArray, payload: ByteArray) {
        val tcp = Pkt.buildTcp(c.dstPort, c.srcPort, seq, ackNum, flags, 65535, options, payload, c.dstIp, c.srcIp)
        out(Pkt.buildIpv4(c.dstIp, c.srcIp, Pkt.TCP, tcp, ipId.incrementAndGet() and 0xffff))
    }

    private fun close(c: Conn, key: Key) {
        if (c.dead) return
        c.dead = true
        conns.remove(key)
        try { c.socket.close() } catch (e: Exception) { }
    }

    fun closeAll() {
        for ((k, c) in conns) {
            c.dead = true
            try { c.socket.close() } catch (e: Exception) { }
            conns.remove(k)
        }
    }
}
