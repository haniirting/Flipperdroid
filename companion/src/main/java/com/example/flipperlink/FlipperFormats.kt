package com.example.flipperlink

// Human-readable summaries of Flipper files (.sub, .rfid, .ibtn, .nfc, .ir, BadUSB .txt).
// Key names follow files shipped in firmware 1.4.3; unknown files fall back to a generic key/value view.
object FlipperFormats {

    private fun hexBytes(v: String?): List<Int> = (v ?: "").trim().split(" ").mapNotNull { it.toIntOrNull(16) }

    // Dallas / Maxim CRC-8 (polynomial 0x8C, reflected) used by iButton ROM codes
    private fun dallasCrc(bytes: List<Int>): Int {
        var crc = 0
        for (b in bytes) {
            var x = b
            for (i in 0 until 8) {
                val mix = (crc xor x) and 1
                crc = crc ushr 1
                if (mix != 0) crc = crc xor 0x8C
                x = x ushr 1
            }
        }
        return crc
    }

    fun summarize(name: String, text: String): String {
        val lower = name.lowercase()
        val kv = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || !l.contains(":")) continue
            val k = l.substringBefore(":").trim()
            if (!kv.containsKey(k)) kv[k] = l.substringAfter(":").trim()
        }
        val sb = StringBuilder()
        when {
            lower.endsWith(".sub") -> {
                sb.append("Sub-GHz file\n")
                kv["Frequency"]?.toLongOrNull()?.let { sb.append("Frequency: %.2f MHz\n".format(it / 1_000_000.0)) }
                for (k in listOf("Preset", "Protocol", "Bit", "Key", "TE")) kv[k]?.let { sb.append("$k: $it\n") }
                val raw = text.lines().filter { it.startsWith("RAW_Data:") }
                if (raw.isNotEmpty()) {
                    val n = raw.sumOf { l -> l.substringAfter(":").trim().split(" ").count { it.toIntOrNull() != null } }
                    sb.append("RAW: $n samples in ${raw.size} lines\n")
                }
                sb.append("\nThis phone has no Sub-GHz radio: the file can be viewed, not transmitted.")
            }
            lower.endsWith(".rfid") -> {
                sb.append("RFID 125 kHz key\n")
                kv.entries.take(12).forEach { sb.append("${it.key}: ${it.value}\n") }
                val data = hexBytes(kv["Data"])
                // same layout as the firmware renders EM4100: FC = byte 2, card = bytes 3..4
                if (kv["Key type"] == "EM4100" && data.size >= 5) {
                    sb.append("\nFC: %03d\nCard: %05d\n".format(data[2], (data[3] shl 8) or data[4]))
                }
                sb.append("\nUse Run in the archive to emulate this key on the Flipper.")
            }
            lower.endsWith(".ibtn") -> {
                sb.append("iButton key\n")
                kv.entries.take(12).forEach { sb.append("${it.key}: ${it.value}\n") }
                val rom = hexBytes(kv["Rom Data"] ?: kv["Data"])
                if (rom.size == 8) {
                    val crc = dallasCrc(rom.subList(0, 7))
                    sb.append("\nFamily code: %02X\nSerial: %s\nCRC: %s\n".format(
                        rom[0], rom.subList(1, 7).joinToString(" ") { "%02X".format(it) },
                        if (crc == rom[7]) "valid" else "wrong (expected %02X)".format(crc)))
                }
                sb.append("\nUse Run in the archive to emulate this key on the Flipper.")
            }
            lower.endsWith(".nfc") -> {
                sb.append("NFC card\n")
                kv.entries.filter { !it.key.startsWith("Block") && !it.key.startsWith("Page") }
                    .take(12).forEach { sb.append("${it.key}: ${it.value}\n") }
            }
            lower.endsWith(".ir") -> {
                val names = text.lines().map { it.trim() }.filter { it.startsWith("name:") }.map { it.substringAfter(":").trim() }
                sb.append("Infrared file: ${names.size} signals\n")
                names.take(15).forEach { sb.append("- $it\n") }
                sb.append("\nOpen it in the IR screen to send NEC signals.")
            }
            lower.endsWith(".txt") && (text.contains("STRING") || text.contains("REM ") || text.contains("DELAY")) -> {
                val lines = text.lines()
                sb.append("BadUSB script: ${lines.size} lines\n")
                sb.append("Comments: ${lines.count { it.startsWith("REM") }}, typed strings: ${lines.count { it.startsWith("STRING") }}\n")
                sb.append("\nThe phone only shows the script; it does not run it.")
            }
            else -> kv.entries.take(15).forEach { sb.append("${it.key}: ${it.value}\n") }
        }
        return sb.toString()
    }
}
