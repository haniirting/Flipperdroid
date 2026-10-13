package com.example.nfcuid

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA

class CardInfo(val title: String, val details: String, val nfcFile: String?)

// Identifies the card type and, for Mifare Ultralight / NTAG cards, reads all readable pages.
// Type names, page counts and the .nfc layout follow the firmware (lib/nfc/protocols/mf_ultralight).
// Cards that need keys (Mifare Classic, bank cards) are only identified, never read.
object NfcCards {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    fun analyze(tag: Tag): CardInfo {
        val uid = hex(tag.id)
        var atqa = "00 00"
        var sak = "00"
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            val at = nfcA.atqa
            if (at.size >= 2) atqa = "%02X %02X".format(at[1], at[0])
            sak = "%02X".format(nfcA.sak.toInt() and 0xFF)
        }
        val head = "Filetype: Flipper NFC device\nVersion: 4\n"
        val common = "# UID, ATQA and SAK are common for all formats\nUID: $uid\nATQA: $atqa\nSAK: $sak\n"
        val uidFile = if (nfcA != null) head + "Device type: ISO14443-3A\n" + common else null

        val mfu = MifareUltralight.get(tag)
        if (mfu != null) return ultralight(mfu, head, common, uid, uidFile)

        val mfc = MifareClassic.get(tag)
        if (mfc != null) {
            val kind = when (mfc.type) {
                MifareClassic.TYPE_PLUS -> "Mifare Plus"
                MifareClassic.TYPE_PRO -> "Mifare Pro"
                else -> "Mifare Classic"
            }
            val size = when (mfc.size) {
                MifareClassic.SIZE_MINI -> "Mini"
                MifareClassic.SIZE_1K -> "1K"
                MifareClassic.SIZE_2K -> "2K"
                MifareClassic.SIZE_4K -> "4K"
                else -> ""
            }
            return CardInfo(
                "$kind $size".trim(),
                "Sectors: ${mfc.sectorCount}, blocks: ${mfc.blockCount}\n" +
                    "Content is protected by keys: only the UID is saved.",
                uidFile
            )
        }

        val iso = IsoDep.get(tag)
        if (iso != null) {
            val hist = iso.historicalBytes?.let { " historical bytes: " + hex(it) } ?: ""
            return CardInfo("ISO14443-4A card", "Smart card (bank card, passport, transit)." + hist + "\nOnly the UID is saved.", uidFile)
        }
        return CardInfo(if (nfcA != null) "ISO14443-3A card" else "NFC tag", "UID only.", uidFile)
    }

    private fun ultralight(mfu: MifareUltralight, head: String, common: String, uid: String, uidFile: String?): CardInfo {
        var type = "Mifare Ultralight"
        var total = 16
        var version: ByteArray? = null
        val pages = ArrayList<ByteArray?>()
        var signature = ByteArray(32)
        val counters = IntArray(3)
        val tearing = Array(3) { "00" }
        try {
            mfu.connect()

            fun tx(cmd: ByteArray): ByteArray? = try {
                mfu.transceive(cmd)
            } catch (e: Exception) {
                try { mfu.close(); mfu.connect() } catch (_: Exception) {}
                null
            }

            val v = tx(byteArrayOf(0x60))                       // GET_VERSION
            if (v != null && v.size >= 8) {
                version = v
                val prodType = v[2].toInt() and 0xFF
                val size = v[6].toInt() and 0xFF
                when {
                    prodType == 4 && size == 0x0F -> { type = "NTAG213"; total = 45 }
                    prodType == 4 && size == 0x11 -> { type = "NTAG215"; total = 135 }
                    prodType == 4 && size == 0x13 -> { type = "NTAG216"; total = 231 }
                    prodType == 3 && size == 0x0B -> { type = "Mifare Ultralight 11"; total = 20 }
                    prodType == 3 && size == 0x0E -> { type = "Mifare Ultralight 21"; total = 41 }
                }
                val sig = tx(byteArrayOf(0x3C, 0x00))           // READ_SIG
                if (sig != null && sig.size >= 32) signature = sig.copyOf(32)
                for (i in 0..2) {
                    val c = tx(byteArrayOf(0x39, i.toByte()))   // READ_CNT
                    if (c != null && c.size >= 3) {
                        counters[i] = (c[0].toInt() and 0xFF) or ((c[1].toInt() and 0xFF) shl 8) or ((c[2].toInt() and 0xFF) shl 16)
                    }
                    val t = tx(byteArrayOf(0x3E, i.toByte()))   // CHECK_TEARING_EVENT
                    if (t != null && t.isNotEmpty()) tearing[i] = "%02X".format(t[0])
                }
            } else if (mfu.type == MifareUltralight.TYPE_ULTRALIGHT_C) {
                type = "Mifare Ultralight C"
                total = 48
            }

            var p = 0
            var ok = true
            while (p < total) {
                val chunk = if (ok) {
                    try { mfu.readPages(p) } catch (e: Exception) { ok = false; null }
                } else null
                for (k in 0 until 4) {
                    if (p + k >= total) break
                    pages.add(if (chunk != null && chunk.size >= (k + 1) * 4) chunk.copyOfRange(k * 4, k * 4 + 4) else null)
                }
                p += 4
            }
        } catch (e: Exception) {
            // keep whatever was read
        } finally {
            try { mfu.close() } catch (_: Exception) {}
        }

        val read = pages.count { it != null }
        val f = StringBuilder(head)
        f.append("Device type: Mifare Ultralight\n").append(common)
        f.append("# Mifare Ultralight specific data\nData format version: 2\nMifare Ultralight type: $type\n")
        f.append("Signature: ${hex(signature)}\n")
        f.append("Mifare version: ${hex(version ?: ByteArray(8))}\n")
        for (i in 0..2) f.append("Counter $i: ${counters[i]}\nTearing $i: ${tearing[i]}\n")
        f.append("Pages total: $total\nPages read: $read\n")
        for (i in 0 until total) f.append("Page $i: ${hex(pages.getOrNull(i) ?: ByteArray(4))}\n")
        f.append("Failed authentication attempts: 0\n")

        val dump = StringBuilder()
        for (i in 0 until minOf(12, pages.size)) {
            dump.append("%02d: %s\n".format(i, pages[i]?.let { hex(it) } ?: "-- -- -- --"))
        }
        val details = "Pages read: $read of $total\n" +
            (if (version != null) "Version: ${hex(version)}\n" else "") +
            (if (read < total) "Some pages are password protected or unreadable.\n" else "") +
            "First pages:\n$dump"
        return CardInfo(type, details, if (read > 0) f.toString() else uidFile)
    }
}
