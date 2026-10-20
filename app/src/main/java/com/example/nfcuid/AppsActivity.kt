package com.example.nfcuid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Apps of Flipper Phone: native apps that draw on a 128x64 orange screen, like the Flipper itself.
// Keys: 0 up, 1 down, 2 right, 3 left, 4 OK, 5 back.

const val K_UP = 0
const val K_DOWN = 1
const val K_RIGHT = 2
const val K_LEFT = 3
const val K_OK = 4
const val K_BACK = 5

private val ORANGE = Color.rgb(255, 130, 0)

object Pix {
    private val black = Paint().apply { color = Color.BLACK; typeface = Typeface.MONOSPACE; textSize = 8f; isAntiAlias = false }
    private val orange = Paint().apply { color = ORANGE; typeface = Typeface.MONOSPACE; textSize = 8f; isAntiAlias = false }
    val fill = Paint().apply { color = Color.BLACK }

    // inv = inverted line (black bar with orange text), the way the Flipper highlights the selected item
    fun text(c: Canvas, s: String, x: Float, y: Float, inv: Boolean = false) {
        if (inv) {
            c.drawRect(x - 1f, y - 7f, x + black.measureText(s) + 1f, y + 2f, fill)
            c.drawText(s, x, y, orange)
        } else {
            c.drawText(s, x, y, black)
        }
    }
}

interface Scene {
    fun draw(c: Canvas)
    fun key(k: Int)
    fun close() {}
}

// The Flipper screen: orange background, scaled 128x64 drawing area
class FlipperDisplay(ctx: android.content.Context) : View(ctx) {
    var scene: Scene? = null

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * 64 / 128)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(ORANGE)
        val s = width / 128f
        c.save()
        c.scale(s, s)
        scene?.draw(c)
        c.restore()
    }
}

// A list with a highlighted item and an optional line at the bottom
class MenuScene(
    private val title: String,
    private val items: List<Pair<String, () -> Unit>>,
    private val footer: () -> String = { "" }
) : Scene {
    private var sel = 0
    private var top = 0
    private val rows = 4

    override fun draw(c: Canvas) {
        Pix.text(c, title.take(20), 2f, 9f)
        c.drawRect(0f, 12f, 128f, 13f, Pix.fill)
        if (items.isEmpty()) {
            Pix.text(c, "(empty)", 2f, 26f)
        } else {
            for (i in 0 until rows) {
                val idx = top + i
                if (idx >= items.size) break
                Pix.text(c, items[idx].first.take(22), 4f, 22f + i * 10f, idx == sel)
            }
        }
        val f = footer()
        if (f.isNotEmpty()) Pix.text(c, f.take(24), 2f, 62f)
    }

    override fun key(k: Int) {
        when (k) {
            K_UP -> if (sel > 0) sel--
            K_DOWN -> if (sel < items.size - 1) sel++
            K_OK -> items.getOrNull(sel)?.second?.invoke()
        }
        if (sel < top) top = sel
        if (sel >= top + rows) top = sel - rows + 1
    }
}

// Bluetooth: scans nearby Bluetooth LE devices and lists them. Flipper devices are marked with *
class BleScene(private val act: AppsActivity) : Scene {
    private val found = LinkedHashMap<String, String>()
    private var msg = "Scanning..."
    private var sel = 0
    private var top = 0
    private var scanner: BluetoothLeScanner? = null
    private var cb: ScanCallback? = null

    init { start() }

    private fun start() {
        val adapter = (act.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            msg = "Turn Bluetooth on"
            return
        }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            msg = "Allow location, then reopen"
            return
        }
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val n = (try { r.device.name } catch (e: SecurityException) { null })
                    ?: r.scanRecord?.deviceName ?: "<no name>"
                val mark = if (n.startsWith("Flipper", true)) "* " else ""
                synchronized(found) { found[r.device.address] = "$mark$n ${r.rssi}dB" }
                act.runOnUiThread { act.redraw() }
            }
        }
        cb = c
        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(c)
        msg = "Scanning..."
    }

    override fun close() {
        cb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }
        cb = null
    }

    override fun draw(c: Canvas) {
        Pix.text(c, "Bluetooth", 2f, 9f)
        c.drawRect(0f, 12f, 128f, 13f, Pix.fill)
        val list = synchronized(found) { found.values.toList() }
        if (list.isEmpty()) {
            Pix.text(c, msg, 2f, 26f)
            return
        }
        for (i in 0 until 4) {
            val idx = top + i
            if (idx >= list.size) break
            Pix.text(c, list[idx].take(23), 2f, 22f + i * 10f, idx == sel)
        }
    }

    override fun key(k: Int) {
        val n = synchronized(found) { found.size }
        when (k) {
            K_UP -> if (sel > 0) sel--
            K_DOWN -> if (sel < n - 1) sel++
        }
        if (sel < top) top = sel
        if (sel >= top + 4) top = sel - 3
    }
}

// Downloads TV remotes from the Flipper-IRDB database on GitHub (the same .ir files the Flipper uses)
object IrDb {
    private const val REPO = "Lucaslhm/Flipper-IRDB"

    fun tvPaths(): List<String> {
        val tree = JSONObject(get("https://api.github.com/repos/$REPO/git/trees/HEAD?recursive=1")).getJSONArray("tree")
        val out = ArrayList<String>()
        for (i in 0 until tree.length()) {
            val o = tree.getJSONObject(i)
            val p = o.getString("path")
            if (o.getString("type") == "blob" && p.endsWith(".ir", true) && p.contains("tv", true)) out.add(p)
        }
        return out.sorted()
    }

    fun fetch(path: String): String {
        val enc = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return get("https://raw.githubusercontent.com/$REPO/HEAD/$enc")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "FlipperPhone")
        try {
            if (c.responseCode != 200) throw Exception("GitHub answered HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

// Reads a Flipper .ir file: parsed signals of the firmware protocols and raw signals
fun parseIrButtons(text: String): List<IrButton> {
    val out = ArrayList<IrButton>()
    var name = ""
    var proto = ""
    var addr = 0
    var cmd = 0
    var freq = 38000
    var raw: IntArray? = null
    var have = false
    fun flush() {
        if (have) {
            val r = raw
            if (r != null && r.isNotEmpty()) out.add(IrButton(name, "", 0, 0, freq, r))
            else if (proto in IrCodes.protocols) out.add(IrButton(name, proto, addr, cmd))
        }
        name = ""; proto = ""; addr = 0; cmd = 0; freq = 38000; raw = null; have = false
    }
    for (line in text.lines()) {
        val l = line.trim()
        when {
            l.startsWith("name:") -> { flush(); name = l.substringAfter(":").trim(); have = true }
            l.startsWith("protocol:") -> proto = l.substringAfter(":").trim()
            l.startsWith("address:") -> addr = leHex(l)
            l.startsWith("command:") -> cmd = leHex(l)
            l.startsWith("frequency:") -> freq = l.substringAfter(":").trim().toIntOrNull() ?: 38000
            l.startsWith("data:") -> raw = l.substringAfter(":").trim().split(" ").mapNotNull { it.toIntOrNull() }.toIntArray()
        }
    }
    flush()
    return out
}

private fun leHex(line: String): Int {
    var v = 0
    line.substringAfter(":").trim().split(" ").filter { it.isNotEmpty() }.take(4).forEachIndexed { i, s ->
        v = v or ((s.toIntOrNull(16) ?: 0) shl (8 * i))
    }
    return v
}

class AppsActivity : Activity() {
    private val stack = ArrayList<Scene>()
    private lateinit var display: FlipperDisplay
    var lastMsg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        display = FlipperDisplay(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(40, 40, 40))
            setPadding(24, 24, 24, 24)
        }
        root.addView(display, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(keyRow(listOf("▲" to K_UP)))
        root.addView(keyRow(listOf("◀" to K_LEFT, "OK" to K_OK, "▶" to K_RIGHT)))
        root.addView(keyRow(listOf("▼" to K_DOWN, "BACK" to K_BACK)))
        setContentView(root)
        push(rootMenu())
    }

    private fun keyRow(keys: List<Pair<String, Int>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((label, k) in keys) {
            val b = Button(this@AppsActivity).apply {
                text = label
                setTextColor(ORANGE)
                setBackgroundColor(Color.BLACK)
                typeface = Typeface.MONOSPACE
                setOnClickListener { this@AppsActivity.onKey(k) }
            }
            addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun rootMenu(): Scene = MenuScene("Apps", listOf(
        "Bluetooth" to { push(BleScene(this)) },
        "IR remotes" to { push(irMenu()) },
        "TV database" to { push(tvMenu()) }
    ))

    private fun txStatus(): String =
        if ((getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true)
            "TX: found" else "TX: NOT found"

    // ---------- IR remotes ----------
    private fun irMenu(): Scene {
        lastMsg = ""
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (r in IrRemotes.list(this)) items.add(r.name to { push(remoteMenu(r)) })
        return MenuScene("IR remotes", items, footer = { lastMsg.ifEmpty { txStatus() } })
    }

    private fun remoteMenu(r: IrRemote): Scene {
        lastMsg = ""
        val items = r.buttons.map { b -> b.label to { lastMsg = sendIr(b) } }
        return MenuScene(r.name, items, footer = { lastMsg.ifEmpty { txStatus() } })
    }

    fun sendIr(b: IrButton): String {
        val ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir?.hasIrEmitter() != true) return "No IR transmitter: not sent"
        val raw = b.raw
        val carrier: Int
        val pattern: IntArray
        if (raw != null) {
            carrier = if (b.freq > 0) b.freq else 38000
            pattern = raw
        } else {
            val sig = IrCodes.build(b.proto, b.addr, b.cmd) ?: return "Unsupported: ${b.proto}"
            carrier = sig.carrier
            pattern = sig.pattern
        }
        ir.transmit(carrier, pattern)
        DolphinState.addDeed(this, "ir", 1)
        return "Sent ${b.label}"
    }

    // ---------- TV database ----------
    private fun tvMenu(): Scene {
        lastMsg = ""
        return MenuScene("TV database", listOf("Load TV list" to { loadTvList() }),
            footer = { lastMsg.ifEmpty { "Flipper-IRDB on GitHub" } })
    }

    private fun loadTvList() {
        lastMsg = "Loading list..."
        redraw()
        Thread {
            try {
                val paths = IrDb.tvPaths()
                runOnUiThread {
                    lastMsg = if (paths.isEmpty()) "No TV files found" else ""
                    replaceTop(tvList(paths))
                }
            } catch (e: Exception) {
                post("Failed: ${e.message}")
            }
        }.start()
    }

    private fun tvList(paths: List<String>): Scene {
        val items = paths.map { p -> p.substringAfterLast('/').removeSuffix(".ir") to { importTv(p) } }
        return MenuScene("TV (${paths.size})", items, footer = { lastMsg.ifEmpty { "OK = add to remotes" } })
    }

    private fun importTv(path: String) {
        lastMsg = "Downloading..."
        redraw()
        Thread {
            try {
                val text = IrDb.fetch(path)
                val buttons = parseIrButtons(text)
                if (buttons.isEmpty()) {
                    post("No supported signals in file")
                } else {
                    val base = path.substringAfterLast('/').removeSuffix(".ir")
                    val brand = base.substringBefore('_', base)
                    val model = if (base.contains('_')) base.substringAfter('_') else ""
                    IrRemotes.save(this, IrRemote(System.currentTimeMillis().toString(), base, "TV", brand, model, buttons))
                    post("Saved ${buttons.size} buttons")
                }
            } catch (e: Exception) {
                post("Failed: ${e.message}")
            }
        }.start()
    }

    private fun post(msg: String) = runOnUiThread {
        lastMsg = msg
        redraw()
    }

    // ---------- stack and keys ----------
    fun push(s: Scene) {
        stack.add(s)
        redraw()
    }

    fun replaceTop(s: Scene) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).close()
        stack.add(s)
        redraw()
    }

    fun redraw() {
        display.scene = stack.lastOrNull()
        display.invalidate()
    }

    private fun pop() {
        if (stack.size <= 1) {
            finish()
            return
        }
        stack.removeAt(stack.size - 1).close()
        redraw()
    }

    private fun onKey(k: Int) {
        if (k == K_BACK) pop() else stack.lastOrNull()?.key(k)
        redraw()
    }

    override fun onBackPressed() {
        pop()
    }

    override fun onDestroy() {
        stack.forEach { it.close() }
        super.onDestroy()
    }
}
