package com.example.nfcuid

// Human-readable summaries of Flipper files (.sub, .rfid, .ibtn, .nfc, .ir, BadUSB .txt).
// Key names follow files shipped in firmware 1.4.3; unknown files fall back to a generic key/value view.
object FlipperFormats {

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
            lower.endsWith(".rfid") || lower.endsWith(".ibtn") -> {
                sb.append(if (lower.endsWith(".rfid")) "RFID 125 kHz key\n" else "iButton key\n")
                kv.entries.take(12).forEach { sb.append("${it.key}: ${it.value}\n") }
                sb.append("\nThis phone has no reader/emulator for this key type.")
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
