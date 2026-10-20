package com.example.nfcuid

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor

// Bad USB on a phone: the phone registers as a Bluetooth keyboard (HID), pairs with a PC and types the script.
// Works on Android 9+ with a Bluetooth chip that supports HID device mode. US keyboard layout on the PC.
class BadUsbActivity : Activity() {

    companion object {
        const val EXTRA_SCRIPT = "script"
        private val KEYBOARD = bytes(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0
        )
    }

    private val bg = Color.rgb(255, 130, 0)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executor { r -> main.post(r) }
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var host: BluetoothDevice? = null
    @Volatile private var running = false
    private var script = ""
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var runBtn: Button

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
            registered = reg
            say(if (reg) "Keyboard ready. Pick your PC below." else "Keyboard not registered")
            refreshDevices()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                host = device
                say("Connected to ${name(device)}")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                host = null
                say("Disconnected")
            }
            refreshButtons()
        }
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Flipper Phone Keyboard", "Keyboard", "Flipper Phone",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, KEYBOARD
            )
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
                BluetoothHidDeviceAppQosSettings.MAX
            )
            h.registerApp(sdp, null, qos, exec, callback)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        script = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(32, 48, 32, 32)
        }
        root.addView(tv("Bad USB (Bluetooth)", 22f, true))
        root.addView(tv("The phone acts as a Bluetooth keyboard. Pair the phone with the PC in the PC's Bluetooth settings first, then connect below and press Run.", 12f))
        status = tv("", 13f)
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        runBtn = btn("Run script on the PC") { runScript() }
        root.addView(runBtn)
        root.addView(btn("Stop") { running = false })
        root.addView(btn("Back") { finish() })
        setContentView(root)
        runBtn.isEnabled = false

        val a = adapter
        if (a == null || !a.isEnabled) {
            say("Turn Bluetooth on")
            return
        }
        if (script.isEmpty()) say("No script loaded")
        a.getProfileProxy(this, proxyListener, BluetoothProfile.HID_DEVICE)
        say("Starting keyboard...")
    }

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.BLACK)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(bg)
        setBackgroundColor(Color.BLACK)
        typeface = Typeface.MONOSPACE
        setOnClickListener { onClick() }
    }

    private fun name(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    private fun say(text: String) {
        status.text = text
    }

    private fun refreshDevices() {
        list.removeAllViews()
        val devices = adapter?.bondedDevices?.toList() ?: emptyList()
        if (devices.isEmpty()) list.addView(tv("No paired devices. Pair the phone with the PC first.", 12f))
        for (d in devices) list.addView(btn("Connect: ${name(d)}") { connect(d) })
        refreshButtons()
    }

    private fun connect(d: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) {
            say("Keyboard not ready yet")
            return
        }
        say("Connecting to ${name(d)}...")
        h.connect(d)
    }

    private fun refreshButtons() {
        runBtn.isEnabled = host != null && !running
    }

    private fun runScript() {
        val h = hid
        val d = host
        if (h == null || d == null) {
            say("Connect to a PC first")
            return
        }
        if (running) return
        running = true
        refreshButtons()
        say("Running script...")
        Thread {
            var result = "Done"
            try {
                for (line in script.lines()) {
                    if (!running) {
                        result = "Stopped"
                        break
                    }
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("REM")) continue
                    runLine(h, d, t)
                }
            } catch (e: Exception) {
                result = "Error: ${e.message}"
            }
            running = false
            main.post {
                say(result)
                refreshButtons()
            }
        }.start()
    }

    private fun runLine(h: BluetoothHidDevice, d: BluetoothDevice, t: String) {
        when {
            t.startsWith("STRINGLN") -> {
                typeText(h, d, t.removePrefix("STRINGLN").removePrefix(" "))
                key(h, d, 0, 0x28)
            }
            t.startsWith("STRING") -> typeText(h, d, t.removePrefix("STRING").removePrefix(" "))
            t.startsWith("DELAY") -> sleep(t.removePrefix("DELAY").trim().toLongOrNull() ?: 0L)
            else -> combo(h, d, t)
        }
    }

    private fun typeText(h: BluetoothHidDevice, d: BluetoothDevice, text: String) {
        for (c in text) {
            val k = charKey(c) ?: continue
            key(h, d, k.mod, k.code)
        }
    }

    // "CTRL ALT DELETE", "GUI r", "ENTER", "SHIFT TAB"
    private fun combo(h: BluetoothHidDevice, d: BluetoothDevice, t: String) {
        var mods = 0
        var code = 0
        for (tok in t.split(" ").filter { it.isNotEmpty() }) {
            val u = tok.uppercase()
            when (u) {
                "CTRL", "CONTROL" -> mods = mods or 0x01
                "SHIFT" -> mods = mods or 0x02
                "ALT" -> mods = mods or 0x04
                "GUI", "WINDOWS", "COMMAND" -> mods = mods or 0x08
                "ENTER" -> code = 0x28
                "ESCAPE", "ESC" -> code = 0x29
                "BACKSPACE" -> code = 0x2A
                "TAB" -> code = 0x2B
                "SPACE" -> code = 0x2C
                "DELETE", "DEL" -> code = 0x4C
                "HOME" -> code = 0x4A
                "END" -> code = 0x4D
                "PAGEUP" -> code = 0x4B
                "PAGEDOWN" -> code = 0x4E
                "RIGHTARROW", "RIGHT" -> code = 0x4F
                "LEFTARROW", "LEFT" -> code = 0x50
                "DOWNARROW", "DOWN" -> code = 0x51
                "UPARROW", "UP" -> code = 0x52
                else -> if (tok.length == 1) {
                    val k = charKey(tok[0])
                    if (k != null) {
                        mods = mods or k.mod
                        code = k.code
                    }
                }
            }
        }
        if (code != 0 || mods != 0) key(h, d, mods, code)
    }

    // one key press and release
    private fun key(h: BluetoothHidDevice, d: BluetoothDevice, mod: Int, code: Int) {
        h.sendReport(d, 0, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        sleep(12)
        h.sendReport(d, 0, ByteArray(8))
        sleep(12)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms.coerceIn(0L, 60000L)) } catch (_: InterruptedException) {}
    }

    override fun onDestroy() {
        running = false
        val h = hid
        if (h != null) {
            try { h.unregisterApp() } catch (_: Exception) {}
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        super.onDestroy()
    }
}

private class KeyStroke(val mod: Int, val code: Int)

private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

// US layout: character -> (modifier, HID usage code)
private fun charKey(c: Char): KeyStroke? = when (c) {
    in 'a'..'z' -> KeyStroke(0, 0x04 + (c - 'a'))
    in 'A'..'Z' -> KeyStroke(0x02, 0x04 + (c - 'A'))
    in '1'..'9' -> KeyStroke(0, 0x1E + (c - '1'))
    '0' -> KeyStroke(0, 0x27)
    '\n' -> KeyStroke(0, 0x28)
    '\t' -> KeyStroke(0, 0x2B)
    ' ' -> KeyStroke(0, 0x2C)
    '-' -> KeyStroke(0, 0x2D); '_' -> KeyStroke(0x02, 0x2D)
    '=' -> KeyStroke(0, 0x2E); '+' -> KeyStroke(0x02, 0x2E)
    '[' -> KeyStroke(0, 0x2F); '{' -> KeyStroke(0x02, 0x2F)
    ']' -> KeyStroke(0, 0x30); '}' -> KeyStroke(0x02, 0x30)
    '\\' -> KeyStroke(0, 0x31); '|' -> KeyStroke(0x02, 0x31)
    ';' -> KeyStroke(0, 0x33); ':' -> KeyStroke(0x02, 0x33)
    '\'' -> KeyStroke(0, 0x34); '"' -> KeyStroke(0x02, 0x34)
    '`' -> KeyStroke(0, 0x35); '~' -> KeyStroke(0x02, 0x35)
    ',' -> KeyStroke(0, 0x36); '<' -> KeyStroke(0x02, 0x36)
    '.' -> KeyStroke(0, 0x37); '>' -> KeyStroke(0x02, 0x37)
    '/' -> KeyStroke(0, 0x38); '?' -> KeyStroke(0x02, 0x38)
    '!' -> KeyStroke(0x02, 0x1E); '@' -> KeyStroke(0x02, 0x1F); '#' -> KeyStroke(0x02, 0x20)
    '$' -> KeyStroke(0x02, 0x21); '%' -> KeyStroke(0x02, 0x22); '^' -> KeyStroke(0x02, 0x23)
    '&' -> KeyStroke(0x02, 0x24); '*' -> KeyStroke(0x02, 0x25); '(' -> KeyStroke(0x02, 0x26)
    ')' -> KeyStroke(0x02, 0x27)
    else -> null
}
