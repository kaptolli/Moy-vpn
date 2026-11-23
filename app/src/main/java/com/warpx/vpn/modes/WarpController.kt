package com.warpx.vpn.modes

import android.content.Context
import com.warpx.vpn.VpnState
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

object WarpController {
    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: Tunnel? = null

    fun connect(context: Context, conf: String) {
        val b = GoBackend(context)
        val cfg = Config.parse(conf.byteInputStream())
        val t = object : Tunnel {
            override fun getName(): String = "warp"
            override fun onStateChange(newState: Tunnel.State) {
                VpnState.log("Warp: ${newState.name}")
            }
        }
        backend = b
        tunnel = t
        b.setState(t, Tunnel.State.UP, cfg)
    }

    fun disconnect() {
        val b = backend ?: return
        val t = tunnel ?: return
        try {
            b.setState(t, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            VpnState.log("Warp stop: ${e.message}")
        }
        backend = null
        tunnel = null
    }
}
