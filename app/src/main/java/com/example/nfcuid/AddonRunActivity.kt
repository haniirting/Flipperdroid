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

// Runs an add-on (.html) in a WebView with network loading blocked. The add-on sees a global object Flipper:
//   Flipper.toast(text)                       short message
//   Flipper.hasIr()                           true if the phone has an IR transmitter
//   Flipper.irSend(protocol, addrHex, cmdHex) send a protocol signal (NEC, NECext, NEC42, NEC42ext, Samsung32,
//                                             RC5, RC5X, RC6, SIRC, SIRC15, SIRC20, Kaseikyo, Pioneer, RCA);
//                                             returns true if it was sent
//   Flipper.irSendRaw(freqHz, "9000 4500 ...") send raw timings in microseconds (mark first)
//   Flipper.level(), Flipper.getXp()          read the dolphin level and XP (read only)
// and a function the add-on defines itself:
//   onNfc(uid)                                called with the card UID (hex) when a card touches the phone
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

    inner class Bridge {
        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@AddonRunActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun hasIr(): Boolean = ir?.hasIrEmitter() == true

        @JavascriptInterface
        fun irSend(proto: String, addr: String, cmd: String): Boolean {
            val a = addr.trim().toIntOrNull(16)
            val c = cmd.trim().toIntOrNull(16)
            if (a == null || c == null || ir?.hasIrEmitter() != true) return false
            val sig = IrCodes.build(proto.trim(), a, c) ?: return false
            ir?.transmit(sig.carrier, sig.pattern)
            return true
        }

        @JavascriptInterface
        fun irSendRaw(freq: Int, timings: String): Boolean {
            if (ir?.hasIrEmitter() != true || freq < 20000 || freq > 60000) return false
            val p = timings.split(" ", ",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..200000 }
            if (p.size < 2 || p.size > 2000) return false
            ir?.transmit(freq, p.toIntArray())
            return true
        }

        @JavascriptInterface
        fun getXp(): Int = DolphinState.xp(this@AddonRunActivity)

        @JavascriptInterface
        fun level(): Int = DolphinState.level(DolphinState.xp(this@AddonRunActivity))
    }
}
