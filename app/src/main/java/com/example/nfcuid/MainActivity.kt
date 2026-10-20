package com.example.nfcuid

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
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
    private var irCarrier = 0          // 0 = by protocol
    private var pendingType = "TV"
    private val reqRemote = 79
    private val reqIr = 77


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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "menu" -> super.onBackPressed()
            "remote", "irAdv" -> showIr()
            else -> showMenu()
        }
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

    // XP weights and the daily limit follow the firmware deeds (NFC read/save = 3, IR send = 1, IR save = 3)
    private fun deed(app: String, weight: Int) {
        DolphinState.addDeed(this, app, weight)
    }

    private fun stopAll() {
        nfcOff()
    }

    // ---------- main menu: the same sections, in the same order, as the original Flipper menu ----------
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
        p.addView(iconBtn("Archive", "MainMenu/FileManager_14") { showArchive() })
        p.addView(iconBtn("NFC", "MainMenu/NFC_14") { showNfc() })
        p.addView(iconBtn("Infrared", "MainMenu/Infrared_14") { showIr() })
        p.addView(iconBtn("Bad USB", "MainMenu/BadUsb_14") { showBadUsb() })
        p.addView(iconBtn("Apps", "MainMenu/Plugins_14") { startActivity(Intent(this, AppsActivity::class.java)) })
        p.addView(iconBtn("Settings", "MainMenu/Settings_14") { showSettings() })
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(p) })
    }

    private val reqBadUsb = 80

    private fun showBadUsb() = showInfo(
        "Bad USB",
        "The phone types the script as a Bluetooth keyboard on a PC.\n\n" +
            "1. Pair the phone with the PC in the PC's Bluetooth settings.\n" +
            "2. Choose a script and connect to the PC.\n\n" +
            "US keyboard layout on the PC. Supports STRING, STRINGLN, DELAY, REM, ENTER, GUI/CTRL/ALT/SHIFT combos.",
        "Choose script and run" to {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, reqBadUsb)
        },
        "View script from the phone" to { openViewer() }
    )

    // ---------- settings ----------
    private fun showSettings() {
        val xp = DolphinState.xp(this)
        showInfo(
            "Settings",
            "Dolphin: level ${DolphinState.level(xp)}, XP $xp\n\n" +
                "Flipper Phone is an unofficial fan project. Dolphin animations, icons and file formats come from " +
                "the Flipper firmware (GPLv3). The Flipper name and logo are trademarks of Flipper Devices Inc.",
            "Reset dolphin XP and level" to {
                AlertDialog.Builder(this)
                    .setMessage("Reset the dolphin to level 1 with 0 XP?")
                    .setPositiveButton("Reset") { _, _ -> DolphinState.reset(this); showMenu() }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            "Delete all saved keys" to {
                AlertDialog.Builder(this)
                    .setMessage("Delete all keys saved by this app?")
                    .setPositiveButton("Delete") { _, _ ->
                        File(filesDir, "archive").listFiles()?.forEach { it.delete() }
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
    }

    // ---------- file viewer ----------
    private fun openViewer() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqView)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return "file"
    }

    private fun isBadUsbScript(name: String, text: String): Boolean =
        name.substringAfterLast('.', "").lowercase() == "txt" && (text.contains("STRING") || text.contains("REM ") || text.contains("DELAY"))

    private fun startBadUsb(text: String) {
        if (text.length > 500_000) {
            Toast.makeText(this, "Script too large", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, BadUsbActivity::class.java).putExtra(BadUsbActivity.EXTRA_SCRIPT, text))
    }

    private fun showFileDialog(name: String, text: String, onDelete: (() -> Unit)? = null) {
        val msg = FlipperFormats.summarize(name, text) + "\n----- file -----\n" + text.take(2500)
        val d = AlertDialog.Builder(this).setTitle(name).setMessage(msg).setNegativeButton("Close", null)
        if (onDelete != null) d.setNeutralButton("Delete") { _, _ -> onDelete() }
        if (isBadUsbScript(name, text)) d.setPositiveButton("Run on this phone") { _, _ -> startBadUsb(text) }
        d.show()
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
            deed("nfc", 3)
        }
    }

    private fun saveNfc() {
        val text = lastNfcFile ?: return
        val name = (lastUid ?: "card") + ".nfc"
        Toast.makeText(this, "Saved: " + exportFile(name, text), Toast.LENGTH_LONG).show()
        deed("nfc", 3)
    }

    // Saves a file to Download/FlipperPhone (Android 10+) or app external folder
    // the dolphin shows the firmware's "SD ok" / "SD bad" animation after a save
    private fun exportFile(name: String, text: String): String {
        // private copy for the Archive screen
        try {
            val d = File(filesDir, "archive").apply { mkdirs() }
            File(d, name).writeText(text)
        } catch (_: Exception) {}
        val r = exportFileRaw(name, text)
        val ev = if (r.startsWith("error")) "L0_SdBad_128x51" else "L0_SdOk_128x51"
        getSharedPreferences("flipper", Context.MODE_PRIVATE).edit().putString("dolphin_event", ev).apply()
        return r
    }

    private fun exportFileRaw(name: String, text: String): String {
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

    // ---------- Infrared: remotes ----------
    private fun hasEmitter() = ir?.hasIrEmitter() == true

    private fun emitterText(): String {
        if (!hasEmitter()) {
            return "✖ IR transmitter: NOT FOUND.\nThis phone cannot send IR signals. You can still add and edit remotes."
        }
        val ranges = try { ir?.carrierFrequencies } catch (e: Exception) { null }
        val r = ranges?.joinToString(", ") { "${it.minFrequency / 1000}-${it.maxFrequency / 1000} kHz" } ?: "unknown"
        return "✔ IR transmitter: FOUND (1 emitter).\nSupported carrier: $r"
    }

    private fun carrierOptions(): List<Int> {
        val ranges = (try { ir?.carrierFrequencies } catch (e: Exception) { null }) ?: return listOf(0)
        val common = listOf(30000, 33000, 36000, 38000, 40000, 56000)
        return listOf(0) + common.filter { f -> ranges.any { f >= it.minFrequency && f <= it.maxFrequency } }
    }

    private fun carrierLabel() = if (irCarrier == 0) "auto (by protocol)" else "${irCarrier / 1000} kHz"

    private fun cycleCarrier() {
        val o = carrierOptions()
        val i = o.indexOf(irCarrier).let { if (it < 0) 0 else it }
        irCarrier = o[(i + 1) % o.size]
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

    private fun showIr() {
        stopAll()
        screen = "ir"
        val p = page()
        p.addView(tv("Infrared", 22f, true))
        p.addView(tv(emitterText(), 13f).apply { setPadding(0, 12, 0, 4) })
        if (hasEmitter()) {
            p.addView(
                tv(
                    "Android gives apps access to one IR emitter, so a choice between several emitters is not " +
                        "possible. You can choose the carrier frequency in a remote.",
                    11f
                )
            )
        }
        p.addView(tv("\nRemotes", 16f, true))
        val remotes = IrRemotes.list(this)
        if (remotes.isEmpty()) p.addView(tv("No remotes yet. Add one: choose the device, brand and model.", 12f))
        for (r in remotes) {
            val sub = listOf(r.type, r.brand, r.model).filter { it.isNotEmpty() }.joinToString("  ·  ")
            p.addView(tv("${r.name}\n$sub", 16f).apply {
                setPadding(0, 18, 0, 18)
                setOnClickListener { showRemote(r) }
            })
        }
        p.addView(btn("Add remote") { addRemote() })
        p.addView(btn("Manual signal (advanced)") { showIrAdvanced() })
        p.addView(btn("Back") { showMenu() })
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(p) })
    }

    private fun addRemote() {
        val types = arrayOf("TV", "Air conditioner", "Audio / Hi-Fi", "Projector", "Set-top box", "Other")
        AlertDialog.Builder(this)
            .setTitle("Device type")
            .setItems(types) { _, i -> chooseSource(types[i]) }
            .show()
    }

    private fun chooseSource(type: String) {
        val presets = IrRemotes.presets.filter { it.type == type }
        val items = ArrayList<String>()
        presets.forEach { items.add("Common codes: ${it.brand}") }
        items.add("Import a .ir file (exact codes for your model)")
        AlertDialog.Builder(this)
            .setTitle(type)
            .setItems(items.toTypedArray()) { _, i ->
                if (i < presets.size) {
                    val pr = presets[i]
                    askText("Model (optional)", "") { model ->
                        val r = IrRemote(
                            System.currentTimeMillis().toString(), "${pr.brand} ${pr.type}", pr.type, pr.brand, model,
                            pr.codes.map { IrButton(it.first, pr.proto, pr.addr, it.second) }
                        )
                        IrRemotes.save(this, r)
                        showRemote(r)
                    }
                } else {
                    pendingType = type
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        this.type = "*/*"
                    }
                    startActivityForResult(intent, reqRemote)
                }
            }
            .show()
    }

    private fun importRemote(fileName: String, text: String) {
        val sigs = parseIr(text)
        if (sigs.isEmpty()) {
            Toast.makeText(this, "No supported signals found in this file", Toast.LENGTH_LONG).show()
            return
        }
        val base = fileName.substringBeforeLast(".")
        askText("Remote name", base) { name ->
            askText("Brand and model (optional)", "") { bm ->
                val parts = bm.split(" ", limit = 2)
                val r = IrRemote(
                    System.currentTimeMillis().toString(), name.ifEmpty { base }, pendingType,
                    parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" },
                    sigs.map { IrButton(it.name, it.proto, it.addr, it.cmd, it.freq, it.raw) }
                )
                IrRemotes.save(this, r)
                showRemote(r)
            }
        }
    }

    private fun sendIrButton(b: IrButton, info: TextView) {
        if (!hasEmitter()) {
            info.text = "This phone has no IR transmitter: nothing was sent"
            return
        }
        val raw = b.raw
        val carrier: Int
        val pattern: IntArray
        if (raw != null) {
            carrier = if (b.freq > 0) b.freq else 38000
            pattern = raw
        } else {
            val sig = IrCodes.build(b.proto, b.addr, b.cmd)
            if (sig == null) {
                info.text = "Unsupported protocol: ${b.proto}"
                return
            }
            carrier = sig.carrier
            pattern = sig.pattern
        }
        ir?.transmit(if (irCarrier != 0) irCarrier else carrier, pattern)
        info.text = "Sent: ${b.label}"
        deed("ir", 1)
    }

    private fun rowOf(parent: LinearLayout, specs: List<Pair<String, String>>, byKey: Map<String, IrButton>, info: TextView) {
        val buttons = specs.mapNotNull { (k, label) -> byKey[k]?.let { b -> btn(label) { sendIrButton(b, info) } } }
        if (buttons.isEmpty()) return
        val row = LinearLayout(this)
        for (b in buttons) {
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 4, 4, 4) })
        }
        parent.addView(row)
    }

    private fun showRemote(r: IrRemote) {
        stopAll()
        screen = "remote"
        val p = page()
        p.addView(tv(r.name, 22f, true))
        p.addView(tv(listOf(r.type, r.brand, r.model).filter { it.isNotEmpty() }.joinToString("  ·  "), 12f))
        p.addView(tv(emitterText(), 12f).apply { setPadding(0, 12, 0, 8) })
        val info = tv("", 12f)
        val carrier = btn("Carrier: ${carrierLabel()}") { }
        carrier.setOnClickListener {
            cycleCarrier()
            carrier.text = "Carrier: ${carrierLabel()}"
        }
        p.addView(carrier)

        val byKey = LinkedHashMap<String, IrButton>()
        val others = ArrayList<IrButton>()
        for (b in r.buttons) {
            val k = IrRemotes.canon(b.label)
            if (k != null && !byKey.containsKey(k)) byKey[k] = b else others.add(b)
        }
        p.addView(tv("", 8f))
        rowOf(p, listOf("power" to "Power", "source" to "Source", "mute" to "Mute"), byKey, info)
        rowOf(p, listOf("vol_up" to "Vol +", "ch_up" to "Ch +"), byKey, info)
        rowOf(p, listOf("vol_down" to "Vol -", "ch_down" to "Ch -"), byKey, info)
        rowOf(p, listOf("up" to "▲"), byKey, info)
        rowOf(p, listOf("left" to "◀", "ok" to "OK", "right" to "▶"), byKey, info)
        rowOf(p, listOf("down" to "▼"), byKey, info)
        rowOf(p, listOf("back" to "Back", "menu" to "Menu", "home" to "Home"), byKey, info)
        rowOf(p, listOf("1" to "1", "2" to "2", "3" to "3"), byKey, info)
        rowOf(p, listOf("4" to "4", "5" to "5", "6" to "6"), byKey, info)
        rowOf(p, listOf("7" to "7", "8" to "8", "9" to "9"), byKey, info)
        rowOf(p, listOf("0" to "0"), byKey, info)
        if (others.isNotEmpty()) {
            p.addView(tv("\nOther buttons", 14f, true))
            for (chunk in others.chunked(2)) {
                val row = LinearLayout(this)
                for (b in chunk) {
                    row.addView(btn(b.label) { sendIrButton(b, info) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 4, 4, 4) })
                }
                p.addView(row)
            }
        }
        p.addView(info)
        p.addView(btn("Edit name / model") {
            askText("Name", r.name) { n ->
                askText("Brand and model", "${r.brand} ${r.model}".trim()) { bm ->
                    if (n.isNotEmpty()) r.name = n
                    val parts = bm.split(" ", limit = 2)
                    r.brand = parts.getOrElse(0) { "" }
                    r.model = parts.getOrElse(1) { "" }
                    IrRemotes.save(this, r)
                    showRemote(r)
                }
            }
        })
        p.addView(btn("Delete remote") {
            AlertDialog.Builder(this)
                .setMessage("Delete ${r.name}?")
                .setPositiveButton("Delete") { _, _ -> IrRemotes.delete(this, r); showIr() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        p.addView(btn("Back") { showIr() })
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(p) })
    }

    // ---------- Infrared: manual signal (advanced) ----------
    private fun showIrAdvanced() {
        stopAll()
        screen = "irAdv"
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
                    deed("ir", 1)
                }
            }
        })
        p.addView(btn("Open .ir file") { openIr() })
        p.addView(btn("Save .ir") { saveIr(a, c) })
        p.addView(info)
        p.addView(btn("Back") { showIr() })
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
        deed("ir", 3)
    }

    private fun openIr() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqIr)
    }

    private class IrSig(
        val name: String, val proto: String, val addr: Int, val cmd: Int,
        val freq: Int = 0, val raw: IntArray? = null
    )

    private fun leInt(line: String): Int {
        var v = 0
        line.substringAfter(":").trim().split(" ").filter { it.isNotEmpty() }.take(4).forEachIndexed { i, s ->
            v = v or ((s.toIntOrNull(16) ?: 0) shl (8 * i))
        }
        return v
    }

    // Reads Flipper .ir files: "parsed" signals of the supported protocols and "raw" signals
    private fun parseIr(text: String): List<IrSig> {
        val out = ArrayList<IrSig>()
        var name = ""
        var proto = ""
        var addr = 0
        var cmd = 0
        var freq = 38000
        var raw: IntArray? = null
        var have = false
        fun flush() {
            val r = raw
            if (have && r != null && r.isNotEmpty()) out.add(IrSig(name, "RAW", 0, 0, freq, r))
            else if (have && proto in IrCodes.protocols) out.add(IrSig(name, proto, addr, cmd))
            name = ""; proto = ""; addr = 0; cmd = 0; freq = 38000; raw = null; have = false
        }
        for (rawLine in text.lines()) {
            val l = rawLine.trim()
            when {
                l.startsWith("name:") -> { flush(); name = l.substringAfter(":").trim(); have = true }
                l.startsWith("protocol:") -> proto = l.substringAfter(":").trim()
                l.startsWith("address:") -> addr = leInt(l)
                l.startsWith("command:") -> cmd = leInt(l)
                l.startsWith("frequency:") -> freq = l.substringAfter(":").trim().toIntOrNull() ?: 38000
                l.startsWith("data:") -> raw = l.substringAfter(":").trim().split(" ").mapNotNull { it.toIntOrNull() }.toIntArray()
            }
        }
        flush()
        return out
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == reqView) {
            val t = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            if (t == null) Toast.makeText(this, "Cannot read the file", Toast.LENGTH_SHORT).show()
            else showFileDialog(displayName(uri), t)
            return
        }
        if (requestCode == reqBadUsb) {
            val t = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            if (t == null) Toast.makeText(this, "Cannot read the file", Toast.LENGTH_SHORT).show()
            else startBadUsb(t)
            return
        }
        if (requestCode == reqRemote) {
            val t = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            if (t == null) Toast.makeText(this, "Cannot read the file", Toast.LENGTH_SHORT).show()
            else importRemote(displayName(uri), t)
            return
        }
        if (requestCode != reqIr) return
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) { null } ?: return
        val list = parseIr(text).take(300)
        if (list.isEmpty()) {
            Toast.makeText(this, "No supported signals found in this file", Toast.LENGTH_LONG).show()
            return
        }
        val labels = list.map {
            if (it.raw != null) "${it.name}  (RAW, ${it.raw.size} samples)" else "${it.name}  (${it.proto} %X / %X)".format(it.addr, it.cmd)
        }
        AlertDialog.Builder(this)
            .setTitle("Pick a signal")
            .setItems(labels.toTypedArray()) { _, i ->
                val sig = list[i]
                if (sig.raw != null) {
                    // raw signals are sent as they are
                    if (ir?.hasIrEmitter() != true) {
                        Toast.makeText(this, "No IR blaster on this phone", Toast.LENGTH_LONG).show()
                    } else {
                        ir?.transmit(sig.freq, sig.raw)
                        Toast.makeText(this, "Sent RAW: ${sig.name}", Toast.LENGTH_SHORT).show()
                        deed("ir", 1)
                    }
                } else {
                    irProto = sig.proto
                    irProtoBtn?.text = "Protocol: $irProto"
                    irAddrEdit?.setText("%02X".format(sig.addr))
                    irCmdEdit?.setText("%02X".format(sig.cmd))
                }
            }
            .show()
    }
}
