package com.warpx.vpn.modes

import java.io.OutputStream

class DpiStrategy(
    val name: String,
    val split: Int = 0,
    val splitDelayMs: Int = 0,
    val fake: String? = null,
    val fakeDelayMs: Int = 0
) {
    fun apply(out: OutputStream, payload: ByteArray) {
        val f = fake
        if (f != null) {
            out.write(f.toByteArray())
            out.flush()
            if (fakeDelayMs > 0) Thread.sleep(fakeDelayMs.toLong())
        }
        if (split > 0 && payload.size > split) {
            out.write(payload, 0, split)
            out.flush()
            if (splitDelayMs > 0) Thread.sleep(splitDelayMs.toLong())
            out.write(payload, split, payload.size - split)
            out.flush()
        } else {
            out.write(payload)
            out.flush()
        }
    }

    companion object {
        fun presets(): List<DpiStrategy> = listOf(
            DpiStrategy("direct", 0, 0, null, 0),
            DpiStrategy("split@1", 1, 30, null, 0),
            DpiStrategy("split@5", 5, 40, null, 0),
            DpiStrategy("split@10", 10, 40, null, 0),
            DpiStrategy("split@20+delay", 20, 80, null, 0),
            DpiStrategy("split@5+bigdelay", 5, 150, null, 0),
            DpiStrategy("fake-http+split", 10, 40, "GET / HTTP/1.1\r\nHost: a.com\r\n\r\n", 60)
        )
    }
}
