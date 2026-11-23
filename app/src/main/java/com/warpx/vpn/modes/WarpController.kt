package com.warpx.vpn.modes

import android.content.Context
import com.warpx.vpn.VpnState
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config

object WarpController {
    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: Tunnel? = null

    fun connect(context: Context, conf: String) {
        val b = GoBackend(context, NoopTunnelActionHandler())
        val cfg = Config.parse(conf.byteInputStream())
        val t = object : Tunnel {
            override fun getName(): String = "warp"
            override fun onStateChange(newState: Tunnel.State) {
                VpnState.log("AWG: ${newState.name}")
            }
            override fun isIpv4ResolutionPreferred(): Boolean = false
            override fun isMetered(): Boolean = false
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
            VpnState.log("AWG stop: ${e.message}")
        }
        backend = null
        tunnel = null
    }
}
