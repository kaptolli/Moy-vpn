package com.warpx.vpn.modes

import java.io.ByteArrayOutputStream

object TlsHello {
    fun build(host: String): ByteArray {
        val sni = host.toByteArray(Charsets.US_ASCII)

        // --- extensions ---
        val ext = ByteArrayOutputStream()

        // SNI
        val sniBody = ByteArrayOutputStream()
        sniBody.write(0)                     // name_type = host_name
        writeU16(sniBody, sni.size)
        sniBody.write(sni)
        val sniExt = ByteArrayOutputStream()
        writeU16(sniExt, sniBody.size())     // server_name list length
        sniExt.write(sniBody.toByteArray())
        writeU16(ext, 0x0000)                // type = server_name
        writeU16(ext, sniExt.size())
        ext.write(sniExt.toByteArray())

        // supported_versions (TLS 1.3 + 1.2)
        val ver = ByteArrayOutputStream()
        ver.write(0x04)                      // versions list length (2 версии)
        writeU16(ver, 0x0304)
        writeU16(ver, 0x0303)
        writeU16(ext, 0x002b)
        writeU16(ext, ver.size())
        ext.write(ver.toByteArray())

        // --- body ---
        val body = ByteArrayOutputStream()
        writeU16(body, 0x0303)               // legacy version TLS 1.2
        body.write(ByteArray(32))            // random
        body.write(0)                        // session id length = 0
        val ciphers = byteArrayOf(
            0x13, 0x01, 0x13, 0x02, 0x13, 0x03,
            0xc0.toByte(), 0x2f, 0xc0.toByte(), 0x30
        )
        writeU16(body, ciphers.size)
        body.write(ciphers)
        body.write(1); body.write(0)         // compression: length 1, null
        writeU16(body, ext.size())
        body.write(ext.toByteArray())

        // --- handshake ---
        val hs = ByteArrayOutputStream()
        hs.write(0x01)                       // client_hello
        writeU24(hs, body.size())
        hs.write(body.toByteArray())

        // --- record ---
        val rec = ByteArrayOutputStream()
        rec.write(0x16)                      // handshake
        rec.write(0x03); rec.write(0x01)     // TLS 1.0 record version
        writeU16(rec, hs.size())
        rec.write(hs.toByteArray())

        return rec.toByteArray()
    }

    private fun writeU16(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 8) and 0xff)
        o.write(v and 0xff)
    }

    private fun writeU24(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 16) and 0xff)
        o.write((v ushr 8) and 0xff)
        o.write(v and 0xff)
    }
}
