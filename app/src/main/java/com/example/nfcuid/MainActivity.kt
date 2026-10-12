package com.example.nfcuid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleResult
import android.content.BroadcastReceiver
import android.content.ContentValues
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
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
    private var lastNfcFile: String? = null
    private var irAddrEdit: EditText? = null
    private var irCmdEdit: EditText? = null
    private var irProtoBtn: Button? = null
    private var irProto = "NEC"
    private val reqIr = 77

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

    private fun iconBtn(text: String, iconDir: String, onClick: () -> Unit): Button {
        val b = btn(text, onClick)
        IconUtil.drawable(this, iconDir, bg)?.let {
            b.setCompoundDrawables(it, null, null, null)
            b.compoundDrawablePadding = 24
        }
        return b
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(32, 48, 32, 32)
    }

    private fun prefs() = getSharedPreferences("flipper", Context.MODE_PRIVATE)

    // XP weights follow the firmware deeds (NFC read/save = 3, IR send = 1, IR save = 3)
    private fun addXp(weight: Int = 1) {
        DolphinState.addXp(this, weight)
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
        val xp = DolphinState.xp(this)
        val level = DolphinState.level(xp)
        val butthurt = DolphinState.butthurt(this)
        val toNext = DolphinState.xpToLevelUp(xp)
        val p = page()
        p.addView(tv("Flipper Phone", 22f, true))
        p.addView(DolphinView(this), LinearLayout.LayoutParams(match, (240 * resources.displayMetrics.density).toInt()))
        p.addView(tv("Lv $level  XP $xp" + (if (toNext > 0) " (+$toNext to next)" else "") + "  Butthurt $butthurt/14", 12f))
        p.addView(iconBtn("NFC", "MainMenu/NFC_14") { showNfc() })
        p.addView(iconBtn("IR", "MainMenu/Infrared_14") { showIr() })
        p.addView(btn("Wi-Fi") { showWifi() })
        p.addView(btn("Bluetooth") { showBle() })
        p.addView(iconBtn("Flipper (BLE)", "MainMenu/Settings_14") { startActivity(Intent(this, FlipperActivity::class.java)) })
        p.addView(iconBtn("PC link (Wi-Fi)", "MainMenu/FileManager_14") { startActivity(Intent(this, PcServerActivity::class.java)) })
        p.addView(iconBtn("Add-ons", "MainMenu/Plugins_14") { startActivity(Intent(this, AddonActivity::class.java)) })
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(p) })
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
        val s = btn("Save .nfc") { saveNfc() }.apply { isEnabled = false }
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
        val card = try { NfcCards.analyze(tag) } catch (e: Exception) { null }
        runOnUiThread {
            lastUid = uid.replace(" ", "")
            lastNfcFile = card?.nfcFile
            uidView?.text = uid
            infoView?.text = (if (card != null) card.title + "\n" + card.details + "\n" else "") + techs
            saveBtn?.isEnabled = card?.nfcFile != null
            addXp(3)
        }
    }

    private fun saveNfc() {
        val text = lastNfcFile ?: return
        val name = (lastUid ?: "card") + ".nfc"
        Toast.makeText(this, "Saved: " + exportFile(name, text), Toast.LENGTH_LONG).show()
        addXp(3)
    }

    // Saves a file to Download/FlipperPhone (Android 10+) or app external folder
    private fun exportFile(name: String, text: String): String {
        // copy for the PC link server
        try {
            val d = File(filesDir, "export").apply { mkdirs() }
            File(d, name).writeText(text)
        } catch (_: Exception) {}
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FlipperPhone")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri == null) {
                    "error"
                } else {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    "Download/FlipperPhone/$name"
                }
            } else {
                val dir = File(getExternalFilesDir(null), "FlipperPhone").apply { mkdirs() }
                File(dir, name).writeText(text)
                dir.absolutePath + "/" + name
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // ---------- IR ----------
    private fun hexLE(v: Int) =
        "%02X %02X %02X %02X".format(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)

    private fun showIr() {
        stopAll()
        screen = "ir"
        val p = page()
        p.addView(tv("IR", 22f, true))
        val protoBtn = btn("Protocol: $irProto") {
            val i = IrCodes.protocols.indexOf(irProto)
            irProto = IrCodes.protocols[(i + 1) % IrCodes.protocols.size]
            irProtoBtn?.text = "Protocol: $irProto"
        }
        irProtoBtn = protoBtn
        val a = EditText(this).apply { hint = "Address hex"; setText("00"); setTextColor(fg); setHintTextColor(Color.DKGRAY) }
        val c = EditText(this).apply { hint = "Command hex"; setText("02"); setTextColor(fg); setHintTextColor(Color.DKGRAY) }
        val info = tv("", 14f)
        irAddrEdit = a
        irCmdEdit = c
        p.addView(protoBtn); p.addView(a); p.addView(c)
        p.addView(btn("Send") {
            val av = a.text.toString().toIntOrNull(16)
            val cv = c.text.toString().toIntOrNull(16)
            if (ir?.hasIrEmitter() != true) {
                info.text = "No IR blaster on this phone"
            } else if (av == null || cv == null) {
                info.text = "Enter hex values"
            } else {
                val sig = IrCodes.build(irProto, av, cv)
                if (sig == null) {
                    info.text = "Unsupported protocol"
                } else {
                    ir?.transmit(sig.carrier, sig.pattern)
                    info.text = "Sent $irProto: address %X, command %X".format(av, cv)
                    addXp()
                }
            }
        })
        p.addView(btn("Open .ir file") { openIr() })
        p.addView(btn("Save .ir") { saveIr(a, c) })
        p.addView(info)
        p.addView(btn("Back") { showMenu() })
        setContentView(p)
        if (ir?.hasIrEmitter() != true) info.text = "No IR blaster on this phone"
    }

    private fun saveIr(a: EditText, c: EditText) {
        val av = a.text.toString().toIntOrNull(16)
        val cv = c.text.toString().toIntOrNull(16)
        if (av == null || cv == null) {
            Toast.makeText(this, "Enter hex values", Toast.LENGTH_SHORT).show()
            return
        }
        // Same layout as Flipper "IR signals file" version 1 (address/command = 4 bytes, least significant first)
        val text = "Filetype: IR signals file\nVersion: 1\n#\nname: Signal\ntype: parsed\nprotocol: $irProto\n" +
            "address: ${hexLE(av)}\ncommand: ${hexLE(cv)}\n"
        val name = "${irProto}_%X_%X.ir".format(av, cv)
        Toast.makeText(this, "Saved: " + exportFile(name, text), Toast.LENGTH_LONG).show()
        addXp(3)
    }

    private fun openIr() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqIr)
    }

    private class IrSig(val name: String, val proto: String, val addr: Int, val cmd: Int)

    private fun leInt(line: String): Int {
        var v = 0
        line.substringAfter(":").trim().split(" ").filter { it.isNotEmpty() }.take(4).forEachIndexed { i, s ->
            v = v or ((s.toIntOrNull(16) ?: 0) shl (8 * i))
        }
        return v
    }

    // Reads Flipper .ir files; keeps "parsed" signals of the protocols this app can send
    private fun parseIr(text: String): List<IrSig> {
        val out = ArrayList<IrSig>()
        var name = ""
        var proto = ""
        var addr = 0
        var cmd = 0
        var have = false
        fun flush() {
            if (have && proto in IrCodes.protocols) out.add(IrSig(name, proto, addr, cmd))
            name = ""; proto = ""; addr = 0; cmd = 0; have = false
        }
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("name:") -> { flush(); name = l.substringAfter(":").trim(); have = true }
                l.startsWith("protocol:") -> proto = l.substringAfter(":").trim()
                l.startsWith("address:") -> addr = leInt(l)
                l.startsWith("command:") -> cmd = leInt(l)
            }
        }
        flush()
        return out
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqIr || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) { null } ?: return
        val list = parseIr(text).take(300)
        if (list.isEmpty()) {
            Toast.makeText(this, "No supported signals found (NEC, NECext, Samsung32, RC5, SIRC)", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pick a signal")
            .setItems(list.map { "${it.name}  (${it.proto} %X / %X)".format(it.addr, it.cmd) }.toTypedArray()) { _, i ->
                irProto = list[i].proto
                irProtoBtn?.text = "Protocol: $irProto"
                irAddrEdit?.setText("%02X".format(list[i].addr))
                irCmdEdit?.setText("%02X".format(list[i].cmd))
            }
            .show()
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
