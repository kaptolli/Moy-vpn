package com.warpx.vpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnState {
    private val buf = ArrayDeque<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > 300) buf.removeFirst()
        }
        listener?.invoke(line)
    }

    fun all(): String = synchronized(buf) { buf.joinToString("\n") }
}
