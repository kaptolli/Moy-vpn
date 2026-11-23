package com.warpx.vpn.doh

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

object DoH {

    data class DnsQuery(val id: Int, val domain: String, val qtype: Int, val question: ByteArray)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = when {
                hostname.contains("dns.google") -> listOf(InetAddress.getByName("8.8.8.8"))
                hostname.contains("cloudflare") -> listOf(InetAddress.getByName("1.1.1.1"))
                else -> Dns.SYSTEM.lookup(hostname)
            }
        })
        .build()

    private val endpoints = listOf(
        "https://dns.google/dns-query",
        "https://cloudflare-dns.com/dns-query"
    )

    fun resolve(domain: String, qtype: Int): List<String> {
        val name = domain.trim().removeSuffix(".").lowercase()
        if (name.isEmpty()) return emptyList()
        DnsCache.ipsForHost(name).takeIf { it.isNotEmpty() }?.let { return it }
        for (ep in endpoints) {
            try {
                val url = ep.toHttpUrl().newBuilder()
                    .addQueryParameter("name", name)
                    .addQueryParameter("type", qtype.toString())
                    .build()
                val req = Request.Builder().url(url)
                    .header("Accept", "application/dns-message")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.bytes() ?: return@use
                    val ips = parseAnswers(body)
                    if (ips.isNotEmpty()) {
                        DnsCache.put(name, ips)
                        return ips
                    }
                }
            } catch (e: Exception) {
                // пробуем следующий endpoint
            }
        }
        return emptyList()
    }

    fun parseQuery(msg: ByteArray): DnsQuery? {
        if (msg.size < 12) return null
        val id = ((msg[0].toInt() and 0xff) shl 8) or (msg[1].toInt() and 0xff)
        val qd = ((msg[4].toInt() and 0xff) shl 8) or (msg[5].toInt() and 0xff)
        if (qd < 1) return null
        var p = 12
        val sb = StringBuilder()
        while (p < msg.size) {
            val l = msg[p].toInt() and 0xff
            if (l == 0) { p++; break }
            if (l and 0xC0 == 0xC0) { p += 2; break }
            val label = String(msg, p + 1, l, Charsets.US_ASCII)
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(label)
            p += l + 1
        }
        if (p + 4 > msg.size) return null
        val qtype = ((msg[p].toInt() and 0xff) shl 8) or (msg[p + 1].toInt() and 0xff)
        val question = msg.copyOfRange(12, p + 4)
        return DnsQuery(id, sb.toString(), qtype, question)
    }

    fun buildResponse(q: DnsQuery, answers: List<InetAddress>): ByteArray {
        val buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(q.id.toShort())
        buf.putShort(0x8180.toShort())      // QR=1, RD/RA=1, rcode=0
        buf.putShort(1)                     // QDCOUNT
        buf.putShort(answers.size.toShort()) // ANCOUNT
        buf.putShort(0)
        buf.putShort(0)
        buf.put(q.question)
        for (ip in answers) {
            buf.putShort(0xC00C.toShort())  // pointer to name at offset 12
            val t = if (ip.address.size == 16) 28 else 1
            buf.putShort(t.toShort())
            buf.putShort(1)                 // IN
            buf.putInt(60)                  // TTL
            buf.putShort(ip.address.size.toShort())
            buf.put(ip.address)
        }
        val arr = ByteArray(buf.position())
        buf.flip()
        buf.get(arr)
        return arr
    }

    private fun parseAnswers(msg: ByteArray): List<String> {
        if (msg.size < 12) return emptyList()
        val qd = ((msg[4].toInt() and 0xff) shl 8) or (msg[5].toInt() and 0xff)
        val an = ((msg[6].toInt() and 0xff) shl 8) or (msg[7].toInt() and 0xff)
        var off = 12
        for (i in 0 until qd) off = skipName(msg, off) + 4
        val out = mutableListOf<String>()
        for (i in 0 until an) {
            off = skipName(msg, off)
            if (off + 10 > msg.size) break
            val type = ((msg[off].toInt() and 0xff) shl 8) or (msg[off + 1].toInt() and 0xff)
            val rdlen = ((msg[off + 8].toInt() and 0xff) shl 8) or (msg[off + 9].toInt() and 0xff)
            val rdata = off + 10
            off = rdata + rdlen
            if (type == 1 && rdlen == 4) {
                out.add(InetAddress.getByAddress(msg.copyOfRange(rdata, rdata + 4)).hostAddress ?: "")
            } else if (type == 28 && rdlen == 16) {
                out.add(InetAddress.getByAddress(msg.copyOfRange(rdata, rdata + 16)).hostAddress ?: "")
            }
        }
        return out
    }

    private fun skipName(msg: ByteArray, off: Int): Int {
        var p = off
        var jumped = false
        var next = off
        while (p < msg.size) {
            val l = msg[p].toInt() and 0xff
            if (l == 0) {
                p++
                if (!jumped) next = p
                return next
            }
            if (l and 0xC0 == 0xC0) {
                if (!jumped) next = p + 2
                p = (((l and 0x3f) shl 8) or (msg[p + 1].toInt() and 0xff))
                jumped = true
                continue
            }
            p += l + 1
        }
        return next
    }
}
