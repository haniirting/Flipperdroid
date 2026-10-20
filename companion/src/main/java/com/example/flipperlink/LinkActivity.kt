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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

// Flipper Link: connect a real Flipper over Bluetooth LE and manage it from the phone.
// Device, key archive (view / run / rename / delete), file manager, remote control, GPIO and app install.
class LinkActivity : Activity() {

    private val bg = Color.rgb(255, 130, 0)
    private val fg = Color.BLACK
    private val match = LinearLayout.LayoutParams.MATCH_PARENT

    private var ble: FlipperBle? = null
    private var ready = false
    private var onHome = true
    private var path = "/ext"
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private val seen = HashSet<String>()
    private lateinit var status: TextView
    private lateinit var items: LinearLayout
    private lateinit var stopBtn: Button
    private val reqImport = 71
    private var bridge: BridgeServer? = null

    private val pinNames = listOf(
        "PC0 (header pin 16)", "PC1 (header pin 15)", "PC3 (header pin 7)", "PB2 (header pin 6)",
        "PB3 (header pin 5)", "PA4 (header pin 4)", "PA6 (header pin 3)", "PA7 (header pin 2)"
    )

    private fun prefs() = getSharedPreferences("link", Context.MODE_PRIVATE)

    // ---------- small UI helpers ----------
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

    private fun rowLong(text: String, onClick: () -> Unit, onLong: () -> Unit) = tv(text, 14f).apply {
        setPadding(0, 18, 0, 18)
        setOnClickListener { onClick() }
        setOnLongClickListener { onLong(); true }
    }

    private fun row(text: String, onClick: () -> Unit) = rowLong(text, onClick) { }

    private fun addEven(parent: LinearLayout, b: View) {
        parent.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun clear(title: String) {
        onHome = false
        items.removeAllViews()
        status.text = title
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // command_status values from flipper.proto
    private fun storageError(code: Int): String = when (code) {
        5 -> "storage not ready"
        6 -> "already exists"
        7 -> "not found"
        9 -> "access denied"
        10 -> "invalid name"
        18 -> "folder is not empty"
        21 -> "no app is running"
        22 -> "app command error"
        else -> "error $code"
    }

    private fun appError(code: Int): String = when (code) {
        16 -> "cannot start (wrong firmware version or damaged file)"
        17 -> "another app is running on the Flipper, close it first"
        4 -> "the Flipper is busy"
        15 -> "invalid parameters"
        else -> storageError(code)
    }

    // ---------- screen ----------
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
        stopBtn = btn("Stop app on Flipper") {
            ble?.appExit()
            status.text = "Stop sent"
        }
        root.addView(stopBtn)
        setContentView(root)
        home()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onHome) home() else super.onBackPressed()
    }

    // ---------- connect ----------
    private fun adapter() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun home() {
        stopScan()
        onHome = true
        items.removeAllViews()
        val b = ble
        stopBtn.visibility = if (ready && b != null) View.VISIBLE else View.GONE
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
        items.addView(btn("GPIO") { gpio() })
        items.addView(btn("Apps") { apps() })
        items.addView(btn("PC bridge (USB)") { bridgeScreen() })
        items.addView(btn("Disconnect") {
            bridge?.stop()
            bridge = null
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
        clear("Scanning... turn on Bluetooth on the Flipper and tap it when it appears")
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
        clear("Device")
        val info = tv("Loading...", 12f)
        items.addView(btn("Back") { home() })
        items.addView(btn("Ping") { b.ping { ok -> status.text = if (ok) "Ping OK" else "Ping failed" } })
        items.addView(btn("Play alert (find my Flipper)") { b.playAlert(); status.text = "Alert sent" })
        items.addView(btn("Sync time from phone") { b.setTime(Calendar.getInstance()); status.text = "Time sent" })
        items.addView(btn("Unlock Flipper screen") { b.unlock(); status.text = "Unlock sent" })
        items.addView(btn("Back up internal storage to SD") {
            status.text = "Creating backup..."
            b.backupCreate("/ext/backup.tar") { ok, c -> status.text = if (ok) "Backup saved: /ext/backup.tar" else "Backup failed: ${storageError(c)}" }
        })
        items.addView(btn("Restore from /ext/backup.tar") {
            confirm("Restore internal storage from /ext/backup.tar? Current data will be replaced.") {
                status.text = "Restoring..."
                b.backupRestore("/ext/backup.tar") { ok, c -> status.text = if (ok) "Restored" else "Restore failed: ${storageError(c)}" }
            }
        })
        items.addView(btn("Reboot") {
            confirm("Reboot the Flipper?") { b.reboot(0); status.text = "Reboot sent" }
        })
        items.addView(btn("Factory reset") {
            confirm("ERASE all data in the Flipper internal storage and reset it? This cannot be undone.") {
                confirm("Are you really sure?") { b.factoryReset(); status.text = "Factory reset sent" }
            }
        })
        items.addView(info)

        val sb = StringBuilder()
        fun show() { info.text = sb.toString() }
        fun mb(x: Long) = "%.1f MB".format(x / 1048576.0)
        b.deviceInfo { list, err ->
            sb.append("DEVICE\n")
            if (list == null) sb.append("failed: $err\n") else list.forEach { sb.append("${it.first}: ${it.second}\n") }
            show()
            b.powerInfo { list2, err2 ->
                sb.append("\nPOWER\n")
                if (list2 == null) sb.append("failed: $err2\n") else list2.forEach { sb.append("${it.first}: ${it.second}\n") }
                show()
                b.protobufVersion { v ->
                    sb.append("\nProtobuf version: ${v ?: "?"}\n")
                    b.dateTime { dt ->
                        sb.append("Flipper time: ${dt ?: "?"}\n")
                        b.appLocked { locked ->
                            sb.append("App running in RPC mode: ${if (locked == null) "?" else if (locked) "yes" else "no"}\n")
                            b.storageInfo("/ext") { total, free, st ->
                                sb.append("SD card: ").append(if (st == 0) "${mb(free)} free of ${mb(total)}\n" else "not available\n")
                                b.storageInfo("/int") { t2, f2, st2 ->
                                    sb.append("Internal: ").append(if (st2 == 0) "${mb(f2)} free of ${mb(t2)}\n" else "not available\n")
                                    show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- archive (key folders) ----------
    private fun archive() {
        clear("Archive")
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
        clear(p)
        items.addView(btn("Back") {
            if (fromArchive && p.count { it == '/' } <= 2) archive()
            else if (p == "/ext" || p == "/int") home()
            else browse(p.substringBeforeLast("/").ifEmpty { "/ext" }, fromArchive)
        })
        val tools = LinearLayout(this)
        addEven(tools, btn("Import file") { pickImport() })
        addEven(tools, btn("New folder") {
            askText("New folder", "") { n ->
                if (n.isNotEmpty()) b.mkdir("$p/$n") { ok, c ->
                    status.text = if (ok) "Folder created" else "Failed: ${storageError(c)}"
                    browse(p, fromArchive)
                }
            }
        })
        items.addView(tools)
        items.addView(tv("Tap a file to view or run it, long-press for more.", 11f))
        b.list(p) { files, err ->
            if (files == null) {
                status.text = "List failed: $err"
            } else {
                if (files.isEmpty()) items.addView(tv("\n(empty)", 14f))
                files.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).forEach { f ->
                    val label = if (f.isDir) "[DIR] ${f.name}" else "${f.name}  (${f.size} B)"
                    items.addView(rowLong(label, { openEntry(p, f, fromArchive) }, { entryActions(p, f, fromArchive) }))
                }
            }
        }
    }

    private fun runnable(name: String) =
        name.substringAfterLast('.', "").lowercase() in listOf("nfc", "sub", "rfid", "ibtn", "ir")

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
                val d = AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setMessage(msg)
                    .setNegativeButton("Close", null)
                    .setNeutralButton("More") { _, _ -> entryActions(p, f, fromArchive) }
                if (runnable(f.name)) d.setPositiveButton("Run") { _, _ -> runKey(p, f.name, text) }
                d.show()
            }
        }
    }

    private fun entryActions(p: String, f: FlipperEntry, fromArchive: Boolean) {
        val b = ble ?: return
        val full = "$p/${f.name}"
        val labels = if (f.isDir) arrayOf("Delete folder", "Rename") else arrayOf("Delete", "Rename", "MD5 checksum")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(labels) { _, i ->
                when (labels[i]) {
                    "Delete", "Delete folder" -> confirm("Delete ${f.name}?") {
                        b.delete(full, f.isDir) { ok, c ->
                            status.text = if (ok) "Deleted" else "Delete failed: ${storageError(c)}"
                            browse(p, fromArchive)
                        }
                    }
                    "Rename" -> askText("Rename", f.name) { n ->
                        if (n.isNotEmpty() && n != f.name) b.rename(full, "$p/$n") { ok, c ->
                            status.text = if (ok) "Renamed" else "Rename failed: ${storageError(c)}"
                            browse(p, fromArchive)
                        }
                    }
                    "MD5 checksum" -> b.md5(full) { sum, e -> status.text = if (sum != null) "MD5: $sum" else "MD5 failed: $e" }
                }
            }
            .show()
    }

    // ---------- run a saved key on the Flipper (RPC mode of the Flipper apps) ----------
    private fun runKey(p: String, name: String, text: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val app = when (ext) {
            "nfc" -> "NFC"
            "sub" -> "Sub-GHz"
            "rfid" -> "125 kHz RFID"
            "ibtn" -> "iButton"
            "ir" -> "Infrared"
            else -> return
        }
        val full = "$p/$name"
        if (ext == "ir") {
            val names = text.lines().map { it.trim() }.filter { it.startsWith("name:") }
                .map { it.substringAfter(":").trim() }.distinct()
            if (names.isEmpty()) {
                status.text = "No signals in this file"
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Send signal")
                .setItems(names.toTypedArray()) { _, i -> launchKey(app, full, names[i], true) }
                .show()
        } else {
            launchKey(app, full, "", ext != "ibtn")   // iButton starts emulating as soon as the file is loaded
        }
    }

    private fun launchKey(app: String, full: String, button: String, press: Boolean) {
        val b = ble ?: return
        val file = full.substringAfterLast('/')
        status.text = "Starting $app..."
        b.startApp(app, "RPC") { ok, code ->
            if (!ok) {
                status.text = "Cannot start $app: " + appError(code)
            } else {
                b.appLoadFile(full) { ok2, _ ->
                    if (!ok2) {
                        b.getAppError { t -> status.text = "Load failed: $t" }
                    } else if (!press) {
                        status.text = "Running $app: $file (press Stop app to end)"
                    } else {
                        b.appPressRelease(button) { ok3, _ ->
                            if (ok3) status.text = "$app: sent $file (press Stop app to end)"
                            else b.getAppError { t -> status.text = "$app failed: $t" }
                        }
                    }
                }
            }
        }
    }

    // ---------- remote control ----------
    private fun remote() {
        val b = ble ?: return
        clear("Remote control")
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

    // ---------- GPIO ----------
    private fun gpio() {
        val b = ble ?: return
        clear("GPIO")
        items.addView(btn("Back") { home() })
        items.addView(tv("Pins are 3.3 V. Wrong wiring can damage the Flipper.", 12f))
        fun res(ok: Boolean, c: Int, okText: String) { status.text = if (ok) okText else "Failed: ${storageError(c)}" }
        val otg = LinearLayout(this)
        addEven(otg, btn("5V check") {
            b.gpioOtg { on -> status.text = "5 V on pin 1: " + (if (on == null) "?" else if (on) "ON" else "OFF") }
        })
        addEven(otg, btn("5V on") {
            confirm("Turn 5 V on pin 1 on?") { b.gpioSetOtg(true) { ok, c -> res(ok, c, "5 V ON") } }
        })
        addEven(otg, btn("5V off") { b.gpioSetOtg(false) { ok, c -> res(ok, c, "5 V OFF") } })
        items.addView(otg)
        for ((i, name) in pinNames.withIndex()) {
            val label = tv(name, 14f, true).apply { setPadding(0, 28, 0, 0) }
            items.addView(label)
            val r1 = LinearLayout(this)
            addEven(r1, btn("IN") { b.gpioSetMode(i, 1) { ok, c -> res(ok, c, "$name: input") } })
            addEven(r1, btn("OUT") { b.gpioSetMode(i, 0) { ok, c -> res(ok, c, "$name: output") } })
            addEven(r1, btn("READ") { b.gpioRead(i) { v -> label.text = "$name = " + (v ?: "?") } })
            items.addView(r1)
            val r2 = LinearLayout(this)
            addEven(r2, btn("LOW") { b.gpioWrite(i, 0) { ok, c -> res(ok, c, "$name = 0") } })
            addEven(r2, btn("HIGH") { b.gpioWrite(i, 1) { ok, c -> res(ok, c, "$name = 1") } })
            addEven(r2, btn("NOPULL") { b.gpioSetPull(i, 0) { ok, c -> res(ok, c, "$name: no pull") } })
            addEven(r2, btn("UP") { b.gpioSetPull(i, 1) { ok, c -> res(ok, c, "$name: pull up") } })
            addEven(r2, btn("DOWN") { b.gpioSetPull(i, 2) { ok, c -> res(ok, c, "$name: pull down") } })
            items.addView(r2)
        }
    }

    // ---------- PC bridge: lets Flipper Desk on a PC use this phone's Bluetooth link ----------
    private fun bridgeScreen() {
        val b = ble ?: return
        clear("PC bridge")
        items.addView(btn("Back") { home() })
        val state = tv(if (bridge == null) "Bridge is off" else "Bridge is on", 14f, true)
        val toggle = btn(if (bridge == null) "Start bridge" else "Stop bridge") { }
        toggle.setOnClickListener {
            if (bridge == null) {
                bridge = BridgeServer(b) { s -> runOnUiThread { state.text = s; status.text = s } }
                bridge?.start(8765)
                toggle.text = "Stop bridge"
            } else {
                bridge?.stop()
                bridge = null
                toggle.text = "Start bridge"
                state.text = "Bridge is off"
            }
        }
        items.addView(toggle)
        items.addView(state)
        items.addView(
            tv(
                "\nUse the Flipper from a PC through this phone:\n" +
                    "1. Connect the phone to the PC with a USB cable and turn on USB debugging.\n" +
                    "2. Press Start bridge here.\n" +
                    "3. On the PC press \"Set up phone (adb)\" in Flipper Desk (or run: adb forward tcp:8765 tcp:8765).\n" +
                    "4. In Flipper Desk choose \"Phone by USB (adb)\" and press Connect.\n\n" +
                    "The bridge listens on the phone's localhost only. Keep this screen open while you work.",
                12f
            )
        )
    }

    // ---------- apps ----------
    private fun apps() {
        clear("Apps")
        items.addView(btn("Back") { home() })
        items.addView(btn("Install add-on from GitHub link") { askGithub() })
        items.addView(btn("Import file from phone (.fap, .nfc, .sub, .ir ...)") { pickImport() })
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
        bridge?.stop()
        stopScan()
        ble?.disconnect()
    }
}
