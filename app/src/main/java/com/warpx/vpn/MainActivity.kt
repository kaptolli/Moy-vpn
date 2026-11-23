package com.warpx.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.warpx.vpn.core.LocalVpnService
import com.warpx.vpn.data.Store
import com.warpx.vpn.modes.AutoTester
import com.warpx.vpn.modes.Mode
import com.warpx.vpn.modes.ModeType
import com.warpx.vpn.modes.WarpController

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var rg: RadioGroup

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                startSelectedMode()
            } else {
                VpnState.log("Разрешение VPN не выдано")
                setStatus(false)
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readWarpProfile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        rg = findViewById(R.id.rgMode)
        rg.check(R.id.rbDpi)

        tvLog.text = VpnState.all()
        VpnState.listener = { line -> runOnUiThread { tvLog.append("\n" + line) } }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { requestVpn() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.btnAutotest).setOnClickListener { runAutotest() }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("text/plain", "*/*"))
        }
    }

    private fun selectedMode(): ModeType = when (rg.checkedRadioButtonId) {
        R.id.rbWarp -> ModeType.WARP
        R.id.rbWhitelist -> ModeType.WHITELIST
        R.id.rbBlock -> ModeType.BLOCK
        else -> ModeType.DPI
    }

    private fun requestVpn() {
        val m = selectedMode()
        Mode.current = m
        if (m == ModeType.WARP) {
            if (Store.listProfiles(this).isEmpty()) {
                Toast.makeText(this, "Сначала импортируй Warp-профиль (.conf)", Toast.LENGTH_LONG).show()
                return
            }
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startSelectedMode()
    }

    private fun startSelectedMode() {
        when (Mode.current) {
            ModeType.WARP -> {
                val p = Store.listProfiles(this).firstOrNull()
                if (p == null) {
                    VpnState.log("Нет профилей Warp")
                    return
                }
                Thread {
                    try {
                        WarpController.connect(this, p.conf)
                        setStatus(true)
                    } catch (e: Exception) {
                        VpnState.log("Warp ошибка: ${e.message}")
                        setStatus(false)
                    }
                }.start()
            }
            else -> {
                val svc = Intent(this, LocalVpnService::class.java)
                svc.action = LocalVpnService.ACTION_START
                ContextCompat.startForegroundService(this, svc)
                setStatus(true)
            }
        }
    }

    private fun disconnect() {
        when (Mode.current) {
            ModeType.WARP -> Thread {
                WarpController.disconnect()
                setStatus(false)
            }.start()
            else -> {
                val svc = Intent(this, LocalVpnService::class.java)
                svc.action = LocalVpnService.ACTION_STOP
                startService(svc)
                setStatus(false)
            }
        }
    }

    private fun runAutotest() {
        VpnState.log("Автотест запущен…")
        Thread {
            for (line in AutoTester.run()) VpnState.log(line)
        }.start()
    }

    private fun readWarpProfile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val name = uri.lastPathSegment ?: "profile"
            Store.addProfile(this, name, text)
            VpnState.log("Профиль '$name' импортирован")
            Toast.makeText(this, "Профиль импортирован", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            VpnState.log("Ошибка импорта: ${e.message}")
        }
    }

    private fun setStatus(on: Boolean) {
        runOnUiThread {
            tvStatus.text = if (on) "Статус: подключено (${Mode.current.name})" else "Статус: отключено"
        }
    }
}
