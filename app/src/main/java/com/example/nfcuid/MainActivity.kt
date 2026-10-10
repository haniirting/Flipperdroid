package com.example.nfcuid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private val bg = Color.rgb(255, 130, 0)   // Flipper-style orange screen
    private val fg = Color.BLACK
    private val match = LinearLayout.LayoutParams.MATCH_PARENT

    private var nfc: NfcAdapter? = null
    private var ir: ConsumerIrManager? = null
    private var screen = "menu"

    private var uidView: TextView? = null
    private var infoView: TextView? = null
    private var saveBtn: Button? = null
    private var lastUid: String? = null

    private var wifiReceiver: BroadcastReceiver? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleCb: ScanCallback? = null
    private val bleSeen = LinkedHashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        showMenu()
    }

    override fun onResume() {
        super.onResume()
        if (screen == "nfc") nfcOn()
    }

    override fun onPause() {
        super.onPause()
        nfcOff()
        stopBle()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != "menu") showMenu() else super.onBackPressed()
    }

    // ---------- helpers ----------
    private fun tv(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(bg)
        setBackgroundColor(fg)
        typeface = Typeface.MONOSPACE
        setOnClickListener { onClick() }
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(32, 48, 32, 32)
    }

    private fun prefs() = getSharedPreferences("flipper", Context.MODE_PRIVATE)

    private fun addXp() {
        prefs().edit().putInt("xp", prefs().getInt("xp", 0) + 1).apply()
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocation() =
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)

    private fun stopAll() {
        nfcOff()
        stopBle()
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiReceiver = null
    }

    // ---------- menu with dolphin ----------
    private fun showMenu() {
        stopAll()
        screen = "menu"
        val xp = prefs().getInt("xp", 0)
        val level = xp / 5 + 1
        val mood = when {
            xp < 3 -> "Hungry... feed me scans"
            xp < 10 -> "Doing fine"
            else -> "Happy!"
        }
        val p = page()
        p.addView(tv("Flipper Phone", 22f, true))
        p.addView(DolphinView(this), LinearLayout.LayoutParams(match, 0, 1f))
        p.addView(tv("Lv $level  XP $xp  $mood", 14f))
        p.addView(btn("NFC") { showNfc() })
        p.addView(btn("IR") { showIr() })
        p.addView(btn("Wi-Fi") { showWifi() })
        p.addView(btn("Bluetooth") { showBle() })
        setContentView(p)
    }

    // ---------- NFC ----------
    private fun nfcOn() {
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    private fun nfcOff() {
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }

    private fun showNfc() {
        stopAll()
        screen = "nfc"
        val p = page()
        p.addView(tv("NFC reader", 22f, true))
        val u = tv("Hold a card to the back of the phone", 18f).apply { setPadding(0, 32, 0, 8) }
        val i = tv("", 14f)
        val s = btn("Save UID") { saveUid() }.apply { isEnabled = false }
        uidView = u; infoView = i; saveBtn = s
        p.addView(u); p.addView(i); p.addView(s)
        p.addView(btn("Back") { showMenu() })
        setContentView(p)
        if (nfc == null) u.text = "This phone has no NFC"
        else if (nfc?.isEnabled == false) i.text = "NFC is off: turn it on in Settings"
        nfcOn()
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString(" ") { "%02X".format(it) }
        val techs = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        runOnUiThread {
            lastUid = uid
            uidView?.text = uid
            infoView?.text = techs
            saveBtn?.isEnabled = true
            addXp()
        }
    }

    private fun saveUid() {
        val uid = lastUid ?: return
        val file = File(filesDir, "uids.txt")
        file.appendText(uid.replace(" ", "") + "\n")
        Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    // ---------- IR ----------
    private fun necPattern(addr: Int, cmd: Int): IntArray {
        val data = intArrayOf(addr, addr.inv() and 0xFF, cmd, cmd.inv() and 0xFF)
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (b in data) for (i in 0 until 8) {
            p.add(560)
            p.add(if (((b shr i) and 1) == 1) 1690 else 560)
        }
        p.add(560)
        return p.toIntArray()
    }

    private fun showIr() {
        stopAll()
        screen = "ir"
        val p = page()
        p.addView(tv("IR (NEC)", 22f, true))
        val a = EditText(this).apply { hint = "Address hex"; setText("00"); setTextColor(fg); setHintTextColor(Color.DKGRAY) }
        val c = EditText(this).apply { hint = "Command hex"; setText("02"); setTextColor(fg); setHintTextColor(Color.DKGRAY) }
        val info = tv("", 14f)
        p.addView(a); p.addView(c)
        p.addView(btn("Send") {
            val av = a.text.toString().toIntOrNull(16)
            val cv = c.text.toString().toIntOrNull(16)
            if (ir?.hasIrEmitter() != true) {
                info.text = "No IR blaster on this phone"
            } else if (av == null || cv == null) {
                info.text = "Enter hex values"
            } else {
                ir?.transmit(38000, necPattern(av and 0xFF, cv and 0xFF))
                info.text = "Sent: addr %02X cmd %02X".format(av and 0xFF, cv and 0xFF)
                addXp()
            }
        })
        p.addView(info)
        p.addView(btn("Back") { showMenu() })
        setContentView(p)
        if (ir?.hasIrEmitter() != true) info.text = "No IR blaster on this phone"
    }

    // ---------- Wi-Fi ----------
    private fun showWifi() {
        stopAll()
        screen = "wifi"
        val p = page()
        p.addView(tv("Wi-Fi scan", 22f, true))
        val out = tv("Press Scan", 12f)
        p.addView(btn("Scan") { scanWifi(out) })
        p.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(match, 0, 1f))
        p.addView(btn("Back") { showMenu() })
        setContentView(p)
    }

    private fun showWifiResults(wm: WifiManager, out: TextView) {
        val list = try { wm.scanResults } catch (e: SecurityException) { null }
        if (list.isNullOrEmpty()) {
            out.text = "No results.\nTurn on Location (GPS) in quick settings and press Scan again"
            return
        }
        out.text = list.sortedByDescending { it.level }.joinToString("\n\n") {
            val name = if (it.SSID.isNullOrEmpty()) "<hidden>" else it.SSID
            "$name\n${it.level} dBm  ${it.frequency} MHz\n${it.capabilities}"
        }
    }

    private fun scanWifi(out: TextView) {
        if (!hasLocation()) {
            askLocation()
            out.text = "Allow location access, then press Scan again"
            return
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            out.text = "Turn Wi-Fi on"
            return
        }
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = showWifiResults(wm, out)
        }
        wifiReceiver = r
        registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        wm.startScan()
        showWifiResults(wm, out)
        addXp()
    }

    // ---------- Bluetooth LE ----------
    private fun showBle() {
        stopAll()
        screen = "ble"
        val p = page()
        p.addView(tv("Bluetooth LE scan", 22f, true))
        val out = tv("Press Start", 12f)
        p.addView(btn("Start") { startBle(out) })
        p.addView(btn("Stop") { stopBle() })
        p.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(match, 0, 1f))
        p.addView(btn("Back") { showMenu() })
        setContentView(p)
    }

    private fun startBle(out: TextView) {
        if (!hasLocation()) {
            askLocation()
            out.text = "Allow location access, then press Start again"
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            out.text = "Turn Bluetooth on"
            return
        }
        stopBle()
        bleSeen.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: BleResult) {
                val d = result.device
                val name = try { d.name } catch (e: SecurityException) { null }
                bleSeen[d.address] = "${name ?: "<no name>"}\n${d.address}  ${result.rssi} dBm"
                out.text = bleSeen.values.joinToString("\n\n")
            }
        }
        bleCb = cb
        bleScanner = adapter.bluetoothLeScanner
        bleScanner?.startScan(cb)
        out.text = "Scanning..."
        addXp()
    }

    private fun stopBle() {
        bleCb?.let { try { bleScanner?.stopScan(it) } catch (_: Exception) {} }
        bleCb = null
    }
}
