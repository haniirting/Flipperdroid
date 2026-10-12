package com.example.nfcuid

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File

// Runs an add-on (HTML + JavaScript) in a WebView with network loading blocked.
// Functions available to the add-on as the global Flipper object:
//   Flipper.toast(text)             show a short message
//   Flipper.xp(n)                   give the dolphin n XP (max 10 per call)
//   Flipper.getXp(), Flipper.level()  current XP and level (levels from firmware 1.4.3)
//   Flipper.irSend(addrHex, cmdHex) send an NEC infrared signal, returns true on success
//   Flipper.irSendProto(protocol, addrHex, cmdHex)  same for NEC, NECext, Samsung32, RC5, SIRC, SIRC15, SIRC20
// and a function you define yourself:
//   onNfc(uid)                      called with the UID (hex) when a card touches the phone
class AddonRunActivity : Activity(), NfcAdapter.ReaderCallback {

    private lateinit var web: WebView
    private var nfc: NfcAdapter? = null
    private var ir: ConsumerIrManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        val html = try { if (path == null) null else File(path).readText() } catch (e: Exception) { null }
        if (html == null) {
            Toast.makeText(this, "Cannot open the add-on", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        nfc = NfcAdapter.getDefaultAdapter(this)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(), "Flipper")
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        setContentView(web)
    }

    override fun onResume() {
        super.onResume()
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        runOnUiThread {
            web.evaluateJavascript("if (window.onNfc) { onNfc('$uid'); }", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::web.isInitialized) web.destroy()
    }

    private fun nec(addr: Int, cmd: Int): IntArray {
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

    inner class Bridge {
        // protocol: NEC, NECext, Samsung32, RC5, SIRC, SIRC15 or SIRC20
        @JavascriptInterface
        fun irSendProto(proto: String, addr: String, cmd: String): Boolean {
            val a = addr.trim().toIntOrNull(16)
            val c = cmd.trim().toIntOrNull(16)
            if (a == null || c == null || ir?.hasIrEmitter() != true) return false
            val sig = IrCodes.build(proto.trim(), a, c) ?: return false
            ir?.transmit(sig.carrier, sig.pattern)
            DolphinState.addXp(this@AddonRunActivity, 1)
            return true
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@AddonRunActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun xp(n: Int) {
            DolphinState.addXp(this@AddonRunActivity, n.coerceIn(0, 10))
        }

        @JavascriptInterface
        fun getXp(): Int = DolphinState.xp(this@AddonRunActivity)

        @JavascriptInterface
        fun level(): Int = DolphinState.level(DolphinState.xp(this@AddonRunActivity))

        @JavascriptInterface
        fun irSend(addr: String, cmd: String): Boolean {
            val a = addr.trim().toIntOrNull(16)
            val c = cmd.trim().toIntOrNull(16)
            if (a == null || c == null || ir?.hasIrEmitter() != true) return false
            ir?.transmit(38000, nec(a and 0xFF, c and 0xFF))
            DolphinState.addXp(this@AddonRunActivity, 1)
            return true
        }
    }
}
