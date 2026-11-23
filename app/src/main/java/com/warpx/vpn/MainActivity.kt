package com.warpx.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
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
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var rg: RadioGroup
    private lateinit var spProfile: Spinner

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) startSelectedMode() else {
                VpnState.log("Разрешение VPN не выдано")
                setStatus(false)
            }
        }

    private val importMany =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris != null && uris.isNotEmpty()) importProfiles(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        rg = findViewById(R.id.rgMode)
        spProfile = findViewById(R.id.spProfile)
        rg.check(R.id.rbDpi)

        tvLog.text = VpnState.all()
        VpnState.listener = { line -> runOnUiThread { tvLog.append("\n" + line) } }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { requestVpn() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.btnAutotest).setOnClickListener { runAutotest() }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importMany.launch(arrayOf("text/plain", "*/*"))
        }
        findViewById<Button>(R.id.btnRotate).setOnClickListener { rotateProfiles() }

        spProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val adapter = parent?.adapter as? ArrayAdapter<*> ?: return
                if (position >= 0 && position < adapter.count) {
                    adapter.getItem(position)?.toString()?.let { Store.setActive(this@MainActivity, it) }
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfileSpinner()
    }

    private fun refreshProfileSpinner() {
        val names = Store.listProfiles(this).map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProfile.adapter = adapter
        val active = Store.activeName(this)
        val idx = names.indexOf(active)
        if (idx >= 0) spProfile.setSelection(idx)
        if (names.isEmpty()) VpnState.log("Профилей Warp нет — импортируй .conf")
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
        if (m == ModeType.WARP && Store.listProfiles(this).isEmpty()) {
            Toast.makeText(this, "Сначала импортируй Warp-профили", Toast.LENGTH_LONG).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startSelectedMode()
    }

    private fun startSelectedMode() {
        when (Mode.current) {
            ModeType.WARP -> {
                val profiles = Store.listProfiles(this)
                val name = Store.activeName(this)
                val p = profiles.firstOrNull { it.name == name } ?: profiles.firstOrNull()
                if (p == null) {
                    VpnState.log("Нет профилей")
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

    private fun rotateProfiles() {
        val profiles = Store.listProfiles(this)
        if (profiles.isEmpty()) {
            VpnState.log("Нет профилей для перебора")
            return
        }
        Mode.current = ModeType.WARP
        val prep = VpnService.prepare(this)
        if (prep != null) {
            vpnLauncher.launch(prep)
            return
        }
        Thread {
            VpnState.log("Перебор ${profiles.size} профилей…")
            for (p in profiles) {
                VpnState.log("→ ${p.name}")
                try {
                    WarpController.connect(this, p.conf)
                } catch (e: Exception) {
                    VpnState.log("✗ ${p.name}: ${e.message}")
                    try { WarpController.disconnect() } catch (_: Exception) { }
                    continue
                }
                var ok = false
                for (i in 0 until 2) {
                    Thread.sleep(1500)
                    if (testInternet()) { ok = true; break }
                }
                if (ok) {
                    Store.setActive(this, p.name)
                    runOnUiThread { refreshProfileSpinner() }
                    VpnState.log("✅ Работает: ${p.name}")
                    setStatus(true)
                    return@Thread
                }
                VpnState.log("✗ ${p.name} не отвечает")
                try { WarpController.disconnect() } catch (_: Exception) { }
            }
            VpnState.log("Ни один профиль не сработал")
            setStatus(false)
        }.start()
    }

    private fun testInternet(): Boolean = try {
        val s = Socket()
        s.connect(InetSocketAddress("1.1.1.1", 443), 4000)
        s.close()
        true
    } catch (e: Exception) {
        false
    }

    private fun runAutotest() {
        VpnState.log("Автотест запущен…")
        Thread {
            for (line in AutoTester.run()) VpnState.log(line)
        }.start()
    }

    private fun importProfiles(uris: List<Uri>) {
        var ok = 0
        for (uri in uris) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: continue
                if (!text.contains("[Interface]")) {
                    VpnState.log("Пропуск ${uri.lastPathSegment}: не WireGuard .conf")
                    continue
                }
                val name = (uri.lastPathSegment ?: "profile").substringAfterLast('/')
                Store.addProfile(this, name, text)
                ok++
            } catch (e: Exception) {
                VpnState.log("Ошибка ${uri.lastPathSegment}: ${e.message}")
            }
        }
        VpnState.log("Импортировано профилей: $ok")
        refreshProfileSpinner()
        Toast.makeText(this, "Импортировано: $ok", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(on: Boolean) {
        runOnUiThread {
            tvStatus.text = if (on) "Статус: подключено (${Mode.current.name})" else "Статус: отключено"
        }
    }
}
