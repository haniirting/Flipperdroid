package com.example.flipperlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

// Flipper Link: connect a real Flipper over Bluetooth LE and manage it from the phone.
// Device info, key archive, file manager, remote control and app install.
class LinkActivity : Activity() {

    private val bg = Color.rgb(255, 130, 0)
    private val fg = Color.BLACK
    private val match = LinearLayout.LayoutParams.MATCH_PARENT

    private var ble: FlipperBle? = null
    private var ready = false
    private var path = "/ext"
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private val seen = HashSet<String>()
    private lateinit var status: TextView
    private lateinit var items: LinearLayout
    private val reqImport = 71

    private fun prefs() = getSharedPreferences("link", Context.MODE_PRIVATE)

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

    private fun row(text: String, onClick: () -> Unit) = tv(text, 14f).apply {
        setPadding(0, 18, 0, 18)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(32, 48, 32, 32)
        }
        root.addView(tv("Flipper Link", 22f, true))
        status = tv("Not connected", 12f)
        root.addView(status)
        items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(items) }, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)
        home()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (ready && items.childCount > 0 && status.text != "Connected") home() else super.onBackPressed()
    }

    // ---------- connect ----------
    private fun adapter() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun home() {
        stopScan()
        items.removeAllViews()
        val b = ble
        if (!ready || b == null) {
            val addr = prefs().getString("addr", null)
            val name = prefs().getString("name", "Flipper")
            if (addr != null) items.addView(btn("Reconnect: $name") { reconnect(addr) })
            items.addView(btn("Scan for Flipper") { scan() })
            return
        }
        status.text = "Connected"
        items.addView(btn("Device") { device() })
        items.addView(btn("Archive (keys)") { archive() })
        items.addView(btn("Files") { browse("/ext", false) })
        items.addView(btn("Remote control") { remote() })
        items.addView(btn("Apps") { apps() })
        items.addView(btn("Disconnect") {
            ble?.disconnect()
            ble = null
            ready = false
            status.text = "Not connected"
            home()
        })
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            status.text = "Allow location access, then press Scan again"
            return
        }
        val a = adapter()
        if (a == null || !a.isEnabled) {
            status.text = "Turn Bluetooth on"
            return
        }
        stopScan()
        seen.clear()
        items.removeAllViews()
        items.addView(btn("Cancel") { home() })
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val d = r.device
                val name = (try { d.name } catch (e: SecurityException) { null }) ?: r.scanRecord?.deviceName
                val uuids = r.scanRecord?.serviceUuids
                val isFlipper = (name?.startsWith("Flipper", true) == true) ||
                    (uuids?.any { it.uuid == FlipperBle.SERVICE } == true)
                if (isFlipper && seen.add(d.address)) {
                    items.addView(row("${name ?: "Flipper"}  ${d.address}") { connect(d, name ?: "Flipper") })
                }
            }
        }
        scanCb = cb
        scanner = a.bluetoothLeScanner
        scanner?.startScan(cb)
        status.text = "Scanning... turn on Bluetooth on the Flipper and tap it when it appears"
    }

    private fun stopScan() {
        scanCb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null
    }

    private fun reconnect(addr: String) {
        val a = adapter()
        if (a == null || !a.isEnabled) {
            status.text = "Turn Bluetooth on"
            return
        }
        connect(a.getRemoteDevice(addr), prefs().getString("name", "Flipper") ?: "Flipper")
    }

    private fun connect(d: BluetoothDevice, name: String) {
        stopScan()
        prefs().edit().putString("addr", d.address).putString("name", name).apply()
        ble?.disconnect()
        ready = false
        ble = FlipperBle(this) { s ->
            status.text = s
            if (s == "Ready") {
                ready = true
                home()
            } else if (s == "Disconnected") {
                ready = false
            }
        }
        ble?.connect(d)
    }

    // ---------- device ----------
    private fun device() {
        val b = ble ?: return
        items.removeAllViews()
        val info = tv("Loading...", 12f)
        items.addView(btn("Back") { home() })
        items.addView(btn("Ping") { b.ping { ok -> status.text = if (ok) "Ping OK" else "Ping failed" } })
        items.addView(btn("Play alert (find my Flipper)") { b.playAlert(); status.text = "Alert sent" })
        items.addView(btn("Sync time from phone") { b.setTime(Calendar.getInstance()); status.text = "Time sent" })
        items.addView(info)
        b.deviceInfo { list, err ->
            val sb = StringBuilder()
            if (list == null) sb.append("Device info failed: $err\n") else list.forEach { sb.append("${it.first}: ${it.second}\n") }
            info.text = sb.toString()
            b.powerInfo { list2, err2 ->
                sb.append("\nPower\n")
                if (list2 == null) sb.append("Power info failed: $err2\n") else list2.forEach { sb.append("${it.first}: ${it.second}\n") }
                info.text = sb.toString()
            }
        }
    }

    // ---------- archive (key folders) ----------
    private fun archive() {
        items.removeAllViews()
        status.text = "Archive"
        items.addView(btn("Back") { home() })
        val cats = listOf(
            "NFC" to "/ext/nfc",
            "Sub-GHz" to "/ext/subghz",
            "RFID 125 kHz" to "/ext/lfrfid",
            "iButton" to "/ext/ibutton",
            "Infrared" to "/ext/infrared",
            "BadUSB" to "/ext/badusb"
        )
        for ((name, p) in cats) items.addView(btn(name) { browse(p, true) })
    }

    // ---------- file browser ----------
    private fun browse(p: String, fromArchive: Boolean) {
        val b = ble ?: return
        path = p
        items.removeAllViews()
        status.text = p
        items.addView(btn("Back") {
            if (fromArchive && p.count { it == '/' } <= 2) archive()
            else if (p == "/ext" || p == "/int") home()
            else browse(p.substringBeforeLast("/").ifEmpty { "/ext" }, fromArchive)
        })
        items.addView(btn("Import file from phone") { pickImport() })
        b.list(p) { files, err ->
            if (files == null) {
                status.text = "List failed: $err"
            } else {
                if (files.isEmpty()) items.addView(tv("\n(empty)", 14f))
                files.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).forEach { f ->
                    val label = if (f.isDir) "[DIR] ${f.name}" else "${f.name}  (${f.size} B)"
                    items.addView(row(label) { openEntry(p, f, fromArchive) })
                }
            }
        }
    }

    private fun openEntry(p: String, f: FlipperEntry, fromArchive: Boolean) {
        val b = ble ?: return
        if (f.isDir) {
            browse("$p/${f.name}", fromArchive)
            return
        }
        status.text = "Reading ${f.name}..."
        b.read("$p/${f.name}") { data, err ->
            if (data == null) {
                status.text = "Read failed: $err"
            } else {
                status.text = p
                val text = String(data)
                val msg = FlipperFormats.summarize(f.name, text) + "\n----- file -----\n" + text.take(2500)
                AlertDialog.Builder(this).setTitle(f.name).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------- remote control ----------
    private fun remote() {
        val b = ble ?: return
        items.removeAllViews()
        status.text = "Remote control"
        val sv = FlipperScreenView(this)
        items.addView(sv, LinearLayout.LayoutParams(match, LinearLayout.LayoutParams.WRAP_CONTENT))
        fun pad(label: String, key: Int) = btn(label) { b.key(key) }
        val r1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        r1.addView(pad("UP", 0))
        val r2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        r2.addView(pad("LEFT", 3)); r2.addView(pad("OK", 4)); r2.addView(pad("RIGHT", 2))
        val r3 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        r3.addView(pad("DOWN", 1)); r3.addView(pad("BACK", 5))
        items.addView(r1); items.addView(r2); items.addView(r3)
        items.addView(btn("Stop and go back") { b.stopScreen(); home() })
        b.startScreen { data, orient -> sv.setFrame(data, orient) }
    }

    // ---------- apps ----------
    private fun apps() {
        items.removeAllViews()
        status.text = "Apps"
        items.addView(btn("Back") { home() })
        items.addView(btn("Install add-on from GitHub link") { askGithub() })
        items.addView(btn("Import file from phone (.fap, .nfc, .sub, .ir ...)") { pickImport() })
    }

    private fun appError(code: Int): String = when (code) {
        16 -> "cannot start (wrong firmware version or damaged file)"
        17 -> "another app is running on the Flipper, close it first"
        4 -> "the Flipper is busy"
        15 -> "invalid parameters"
        else -> "error $code"
    }

    private fun askGithub() {
        if (ble == null) {
            status.text = "Connect to a Flipper first"
            return
        }
        val input = EditText(this).apply {
            hint = "https://github.com/user/repo/..."
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Install add-on from GitHub")
            .setMessage(
                "Paste a link to a .fap file or to a repo with a release. " +
                    "The .fap runs on your Flipper: install only add-ons you trust, built for your firmware version. " +
                    "Keep the Flipper on its main screen."
            )
            .setView(input)
            .setPositiveButton("Install + run") { _, _ -> installFrom(input.text.toString(), true) }
            .setNeutralButton("Install only") { _, _ -> installFrom(input.text.toString(), false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installFrom(link: String, launch: Boolean) {
        val b = ble ?: return
        status.text = "Downloading..."
        Thread {
            try {
                val r = GithubInstaller.fetch(link)
                val dest = "/ext/apps/Misc/" + r.name
                if (dest.any { it.code > 127 }) throw Exception("The Flipper accepts ASCII-only file names")
                runOnUiThread {
                    status.text = "Sending ${r.name} (${r.data.size} B)..."
                    b.upload(dest, r.data) { ok, err ->
                        if (!ok) {
                            status.text = "Send failed: $err"
                        } else if (!launch) {
                            status.text = "Installed: $dest"
                        } else {
                            status.text = "Starting ${r.name}..."
                            b.startApp(dest, "") { started, code ->
                                status.text = if (started) "Running on the Flipper: ${r.name}"
                                else "Installed, but did not start: " + appError(code)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed: ${e.message}" }
            }
        }.start()
    }

    // ---------- import a file from the phone ----------
    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqImport)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return "file"
    }

    private fun destFor(name: String): String {
        val dir = when (name.substringAfterLast('.', "").lowercase()) {
            "nfc" -> "/ext/nfc"
            "sub" -> "/ext/subghz"
            "rfid" -> "/ext/lfrfid"
            "ibtn" -> "/ext/ibutton"
            "ir" -> "/ext/infrared"
            "fap" -> "/ext/apps/Misc"
            else -> path
        }
        return "$dir/$name"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val b = ble
        if (b == null) {
            status.text = "Connect to a Flipper first"
            return
        }
        val name = displayName(uri)
        val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) {
            status.text = "Cannot read the file"
            return
        }
        if (bytes.size > 8_000_000) {
            status.text = "File too large (max 8 MB)"
            return
        }
        val dest = destFor(name)
        if (dest.any { it.code > 127 }) {
            status.text = "The Flipper accepts ASCII-only file paths"
            return
        }
        status.text = "Sending $name (${bytes.size} B) to $dest ..."
        b.upload(dest, bytes) { ok, err -> status.text = if (ok) "Sent to $dest" else "Send failed: $err" }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        ble?.disconnect()
    }
}
