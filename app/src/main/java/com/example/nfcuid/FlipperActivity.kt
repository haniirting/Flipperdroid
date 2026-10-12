package com.example.nfcuid

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

// Connects to a real Flipper over Bluetooth LE, browses the SD card and shows files.
// Also opens any Flipper file stored on the phone.
class FlipperActivity : Activity() {

    private val bg = Color.rgb(255, 130, 0)
    private val fg = Color.BLACK
    private val match = LinearLayout.LayoutParams.MATCH_PARENT

    private var ble: FlipperBle? = null
    private var path = "/ext"
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private val seen = HashSet<String>()
    private lateinit var status: TextView
    private lateinit var items: LinearLayout
    private val reqOpen = 55
    private val reqSend = 56

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
        root.addView(tv("Flipper (Bluetooth)", 22f, true))
        status = tv("Turn on Bluetooth on the Flipper, then press Scan", 12f)
        root.addView(status)
        root.addView(btn("Scan for Flipper") { scan() })
        root.addView(btn("Open Flipper file from phone") { openFile() })
        items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(items) }, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(btn("Back") { finish() })
        setContentView(root)
    }

    // ---------- scan and connect ----------
    private fun scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            status.text = "Allow location access, then press Scan again"
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Turn Bluetooth on"
            return
        }
        stopScan()
        seen.clear()
        items.removeAllViews()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val d = r.device
                val name = (try { d.name } catch (e: SecurityException) { null }) ?: r.scanRecord?.deviceName
                val uuids = r.scanRecord?.serviceUuids
                val isFlipper = (name?.startsWith("Flipper", true) == true) ||
                    (uuids?.any { it.uuid == FlipperBle.SERVICE } == true)
                if (isFlipper && seen.add(d.address)) {
                    items.addView(row("${name ?: "Flipper"}  ${d.address}") { connect(d) })
                }
            }
        }
        scanCb = cb
        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(cb)
        status.text = "Scanning... tap your Flipper when it appears"
    }

    private fun stopScan() {
        scanCb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null
    }

    private fun connect(d: BluetoothDevice) {
        stopScan()
        items.removeAllViews()
        ble?.disconnect()
        ble = FlipperBle(this) { s ->
            status.text = s
            if (s == "Ready") browse()
        }
        ble?.connect(d)
    }

    // ---------- browse the Flipper SD card ----------
    private fun browse() {
        val b = ble ?: return
        items.removeAllViews()
        status.text = "Folder: $path"
        items.addView(row("[ Ping ]") { b.ping { ok -> status.text = if (ok) "Ping OK" else "Ping failed" } })
        items.addView(row("[ Install add-on from GitHub link ]") { askGithub() })
        items.addView(row("[ Remote: screen + keys ]") { showRemote() })
        items.addView(row("[ Send file from phone to this folder ]") { pickSend() })
        items.addView(row("[ /ext  SD card ]") { path = "/ext"; browse() })
        if (path != "/ext" && path != "/int" && path != "/") {
            items.addView(row("[ .. up ]") { path = path.substringBeforeLast("/").ifEmpty { "/ext" }; browse() })
        }
        b.list(path) { files, err ->
            if (files == null) {
                status.text = "List failed: $err"
            } else {
                files.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).forEach { f ->
                    val label = if (f.isDir) "[DIR] ${f.name}" else "${f.name}  (${f.size} B)"
                    items.addView(row(label) { openEntry(f) })
                }
            }
        }
    }

    private fun openEntry(f: FlipperEntry) {
        val b = ble ?: return
        if (f.isDir) {
            path = "$path/${f.name}"
            browse()
            return
        }
        status.text = "Reading ${f.name}..."
        b.read("$path/${f.name}") { data, err ->
            if (data == null) {
                status.text = "Read failed: $err"
            } else {
                status.text = "Folder: $path"
                show(f.name, String(data))
            }
        }
    }

    private fun show(name: String, text: String) {
        val msg = FlipperFormats.summarize(name, text) + "\n----- file -----\n" + text.take(2500)
        AlertDialog.Builder(this).setTitle(name).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------- install an add-on (.fap) from a GitHub link and start it ----------
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

    private fun appError(code: Int): String = when (code) {
        16 -> "cannot start (wrong firmware version or damaged file)"
        17 -> "another app is running on the Flipper, close it first"
        4 -> "the Flipper is busy"
        15 -> "invalid parameters"
        else -> "error $code"
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

    // ---------- remote screen and keys ----------
    private fun showRemote() {
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
        items.addView(row("[ Stop and go back ]") { b.stopScreen(); browse() })
        b.startScreen { data, orient -> sv.setFrame(data, orient) }
    }

    // ---------- send a file from the phone to the Flipper ----------
    private fun pickSend() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqSend)
    }

    private fun sendFile(uri: Uri) {
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
        val dest = if (name.endsWith(".fap") && !path.startsWith("/ext/apps")) "/ext/apps/Misc/$name" else "$path/$name"
        if (dest.any { it.code > 127 }) {
            status.text = "The Flipper accepts ASCII-only file paths"
            return
        }
        status.text = "Sending $name (${bytes.size} B) to $dest ..."
        b.upload(dest, bytes) { ok, err -> status.text = if (ok) "Sent to $dest" else "Send failed: $err" }
    }

    // ---------- open a file stored on the phone ----------
    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqOpen)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return "file"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == reqOpen) {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null } ?: return
            show(displayName(uri), text)
        } else if (requestCode == reqSend) {
            sendFile(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        ble?.disconnect()
    }
}
