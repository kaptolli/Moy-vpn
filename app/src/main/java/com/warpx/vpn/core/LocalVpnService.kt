package com.warpx.vpn.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.warpx.vpn.MainActivity
import com.warpx.vpn.R
import com.warpx.vpn.VpnState
import com.warpx.vpn.modes.Mode
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.warpx.vpn.START"
        const val ACTION_STOP = "com.warpx.vpn.STOP"
        const val NOTIF_ID = 1
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var running = false
    private var thread: Thread? = null
    private var tcp: TcpForwarder? = null
    private var udp: UdpForwarder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        startForegroundCompat()
        try {
            val builder = Builder()
                .setSession("WarpX")
                .addAddress("10.8.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.8.0.1")
                .setMtu(1500)
            tunFd = builder.establish()
        } catch (e: Exception) {
            VpnState.log("Не удалось поднять VPN: ${e.message}")
            stopSelf()
            return
        }
        val fd = tunFd ?: return
        val out = FileOutputStream(fd.fileDescriptor)
        val writer: (ByteArray) -> Unit = { bytes -> synchronized(out) { out.write(bytes) } }
        tcp = TcpForwarder(this, writer)
        udp = UdpForwarder(this, writer)
        running = true
        VpnState.log("VPN запущен (${Mode.current.name})")

        thread = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32768)
            while (running) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) continue
                when (buf[0].toInt() ushr 4) {
                    4 -> {
                        when (buf[9].toInt() and 0xff) {
                            Pkt.TCP -> tcp?.handle(buf, n)
                            Pkt.UDP -> udp?.handle(buf, n)
                        }
                    }
                    else -> { /* IPv6: не обрабатываем в v1 */ }
                }
            }
        }.also { it.name = "tun-reader" }
        thread!!.start()
    }

    private fun stopVpn() {
        running = false
        try { tunFd?.close() } catch (e: Exception) { }
        tunFd = null
        tcp?.closeAll()
        udp?.closeAll()
        VpnState.log("VPN остановлен")
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { tunFd?.close() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "vpn")
            .setContentTitle("WarpX")
            .setContentText("VPN активен")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
