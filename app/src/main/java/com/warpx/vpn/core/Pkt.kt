package com.warpx.vpn.core

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Pkt {
    const val TCP = 6
    const val UDP = 17
    const val IPV4 = 4

    fun bytesToInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
        ((b[off + 1].toInt() and 0xff) shl 16) or
        ((b[off + 2].toInt() and 0xff) shl 8) or
        (b[off + 3].toInt() and 0xff)

    fun intToBytes(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    fun inet(ip: Int): InetAddress = InetAddress.getByAddress(intToBytes(ip))

    fun buildIpv4(srcIp: Int, dstIp: Int, proto: Int, payload: ByteArray, id: Int): ByteArray {
        val total = 20 + payload.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45)                     // version 4, IHL 5
        buf.put(0)                        // TOS
        buf.putShort(total.toShort())
        buf.putShort(id.toShort())
        buf.putShort(0)                   // flags + fragment offset
        buf.put(64)                       // TTL
        buf.put(proto.toByte())
        buf.putShort(0)                   // checksum placeholder
        buf.putInt(srcIp)
        buf.putInt(dstIp)
        buf.put(payload)
        val arr = buf.array()
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((arr[i].toInt() and 0xff) shl 8) or (arr[i + 1].toInt() and 0xff)
            i += 2
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        val csum = sum.inv() and 0xffff
        arr[10] = ((csum ushr 8) and 0xff).toByte()
        arr[11] = (csum and 0xff).toByte()
        return arr
    }

    fun buildTcp(
        srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, window: Int,
        options: ByteArray, payload: ByteArray, srcIp: Int, dstIp: Int
    ): ByteArray {
        val dataOffset = (20 + options.size) / 4
        val tcp = ByteBuffer.allocate(20 + options.size + payload.size).order(ByteOrder.BIG_ENDIAN)
        tcp.putShort(srcPort.toShort())
        tcp.putShort(dstPort.toShort())
        tcp.putInt(seq)
        tcp.putInt(ack)
        tcp.put((dataOffset shl 4).toByte())   // data offset + reserved
        tcp.put(flags.toByte())
        tcp.putShort(window.toShort())
        tcp.putShort(0)                        // checksum placeholder
        tcp.putShort(0)                        // urgent pointer
        if (options.isNotEmpty()) tcp.put(options)
        if (payload.isNotEmpty()) tcp.put(payload)
        val arr = tcp.array()
        var sum = 0
        sum += (srcIp ushr 16) and 0xffff
        sum += srcIp and 0xffff
        sum += (dstIp ushr 16) and 0xffff
        sum += dstIp and 0xffff
        sum += 6
        sum += arr.size
        var i = 0
        while (i < arr.size) {
            var w = (arr[i].toInt() and 0xff) shl 8
            if (i + 1 < arr.size) w = w or (arr[i + 1].toInt() and 0xff)
            sum += w
            i += 2
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        val csum = sum.inv() and 0xffff
        arr[16] = ((csum ushr 8) and 0xff).toByte()
        arr[17] = (csum and 0xff).toByte()
        return arr
    }
}
