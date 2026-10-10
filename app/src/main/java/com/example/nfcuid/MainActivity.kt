package com.example.nfcuid

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private var nfc: NfcAdapter? = null
    private var ir: ConsumerIrManager? = null

    private lateinit var uidView: TextView
    private lateinit var infoView: TextView
    private lateinit var saveBtn: Button
    private var lastUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }

        // ---- NFC section ----
        root.addView(TextView(this).apply {
            text = "NFC"
            textSize = 22f
        })
        uidView = TextView(this).apply {
            text = "Hold a card to the back of the phone"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        infoView = TextView(this).apply { textSize = 14f }
        saveBtn = Button(this).apply {
            text = "Save UID to file"
            isEnabled = false
            setOnClickListener { saveUid() }
        }
        root.addView(uidView)
        root.addView(infoView)
        root.addView(saveBtn)

        // ---- IR section ----
        root.addView(TextView(this).apply {
            text = "IR (NEC)"
            textSize = 22f
            setPadding(0, 48, 0, 8)
        })
        val addrEdit = EditText(this).apply { hint = "Address (hex, e.g. 00)"; setText("00") }
        val cmdEdit = EditText(this).apply { hint = "Command (hex, e.g. 02)"; setText("02") }
        val irBtn = Button(this).apply { text = "Send IR" }
        val irInfo = TextView(this).apply { textSize = 14f }
        root.addView(addrEdit)
        root.addView(cmdEdit)
        root.addView(irBtn)
        root.addView(irInfo)
        setContentView(root)

        if (nfc == null) {
            uidView.text = "This phone has no NFC"
        } else if (nfc?.isEnabled == false) {
            infoView.text = "NFC is off: turn it on in Settings"
        }

        if (ir?.hasIrEmitter() != true) {
            irBtn.isEnabled = false
            irInfo.text = "No IR blaster on this phone"
        }
        irBtn.setOnClickListener {
            val a = addrEdit.text.toString().toIntOrNull(16)
            val c = cmdEdit.text.toString().toIntOrNull(16)
            if (a == null || c == null) {
                Toast.makeText(this, "Enter hex values", Toast.LENGTH_SHORT).show()
            } else {
                ir?.transmit(38000, necPattern(a and 0xFF, c and 0xFF))
                irInfo.text = "Sent: addr %02X cmd %02X".format(a and 0xFF, c and 0xFF)
            }
        }
    }

    // NEC: 9 ms mark + 4.5 ms space, 32 bits LSB first, final 560 us mark
    private fun necPattern(addr: Int, cmd: Int): IntArray {
        val data = longArrayOf(
            addr.toLong(), (addr.inv() and 0xFF).toLong(),
            cmd.toLong(), (cmd.inv() and 0xFF).toLong()
        )
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (byte in data) {
            for (i in 0 until 8) {
                p.add(560)
                p.add(if ((byte shr i) and 1L == 1L) 1690 else 560)
            }
        }
        p.add(560)
        return p.toIntArray()
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
        nfc?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString(" ") { "%02X".format(it) }
        val techs = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        runOnUiThread {
            lastUid = uid
            uidView.text = uid
            infoView.text = techs
            saveBtn.isEnabled = true
        }
    }

    private fun saveUid() {
        val uid = lastUid ?: return
        val file = File(filesDir, "uids.txt")
        file.appendText(uid.replace(" ", "") + "\n")
        Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
