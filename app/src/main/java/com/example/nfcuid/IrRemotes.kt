package com.example.nfcuid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// One signal of a remote: a protocol signal (proto + address + command) or a RAW signal (freq + raw timings)
class IrButton(
    val label: String, val proto: String, val addr: Int, val cmd: Int,
    val freq: Int = 0, val raw: IntArray? = null
)

class IrRemote(
    val id: String, var name: String, val type: String, var brand: String, var model: String,
    val buttons: List<IrButton>
)

// A built-in set of common codes for a brand
class Preset(val type: String, val brand: String, val proto: String, val addr: Int, val codes: List<Pair<String, Int>>)

object IrRemotes {

    private fun dir(ctx: Context) = File(ctx.filesDir, "remotes").apply { mkdirs() }

    fun list(ctx: Context): List<IrRemote> =
        (dir(ctx).listFiles()?.filter { it.extension == "json" } ?: emptyList())
            .sortedBy { it.name }.mapNotNull { load(it) }

    private fun load(f: File): IrRemote? = try {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("buttons")
        val buttons = ArrayList<IrButton>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val ra = b.optJSONArray("raw")
            val raw = if (ra != null) IntArray(ra.length()) { ra.getInt(it) } else null
            buttons.add(
                IrButton(b.getString("label"), b.optString("proto", ""), b.optInt("addr", 0), b.optInt("cmd", 0),
                    b.optInt("freq", 0), raw)
            )
        }
        IrRemote(o.getString("id"), o.getString("name"), o.getString("type"), o.optString("brand", ""),
            o.optString("model", ""), buttons)
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, r: IrRemote) {
        val arr = JSONArray()
        for (b in r.buttons) {
            val o = JSONObject().put("label", b.label).put("proto", b.proto).put("addr", b.addr)
                .put("cmd", b.cmd).put("freq", b.freq)
            val raw = b.raw
            if (raw != null) {
                val ra = JSONArray()
                for (v in raw) ra.put(v)
                o.put("raw", ra)
            }
            arr.put(o)
        }
        val o = JSONObject().put("id", r.id).put("name", r.name).put("type", r.type)
            .put("brand", r.brand).put("model", r.model).put("buttons", arr)
        File(dir(ctx), r.id + ".json").writeText(o.toString())
    }

    fun delete(ctx: Context, r: IrRemote) {
        File(dir(ctx), r.id + ".json").delete()
    }

    // Maps a button name (from a Flipper .ir file or a preset) to a standard remote key
    fun canon(label: String): String? {
        val s = label.lowercase().replace(Regex("[^a-z0-9+\\-]"), "")
        val up = s.contains("up") || s.contains("+") || s.contains("plus") || s.contains("next") || s.contains("inc")
        val down = s.contains("down") || s.contains("dn") || s.contains("-") || s.contains("minus") ||
            s.contains("prev") || s.contains("dec")
        return when {
            s.contains("power") || s == "pwr" || s == "onoff" || s == "standby" || s == "on" || s == "off" -> "power"
            s.contains("mute") -> "mute"
            s.startsWith("vol") -> if (up && !down) "vol_up" else if (down) "vol_down" else null
            s.startsWith("ch") || s.startsWith("prog") || s.startsWith("page") ->
                if (up && !down) "ch_up" else if (down) "ch_down" else null
            s in setOf("up", "arrowup", "navup", "cursorup", "dpadup") -> "up"
            s in setOf("down", "arrowdown", "navdown", "cursordown", "dpaddown") -> "down"
            s in setOf("left", "arrowleft", "navleft", "cursorleft", "dpadleft") -> "left"
            s in setOf("right", "arrowright", "navright", "cursorright", "dpadright") -> "right"
            s in setOf("ok", "enter", "select", "center", "centre", "dpadcenter") -> "ok"
            s in setOf("back", "return", "exit") -> "back"
            s == "menu" || s == "settings" -> "menu"
            s == "home" || s == "smarthub" -> "home"
            s in setOf("source", "input", "tvav", "av", "tvinput") -> "source"
            Regex("^(num|digit|number|key)?(\\d)$").matches(s) -> s.last().toString()
            else -> null
        }
    }

    private fun digits(f: (Int) -> Int) = (0..9).map { it.toString() to f(it) }

    // Common codes of well-known TV brands. They do not fit every model: for exact codes import the
    // model's .ir file (for example from the Flipper IR database).
    val presets: List<Preset> = listOf(
        Preset(
            "TV", "Samsung", "Samsung32", 0x07,
            listOf(
                "Power" to 0x02, "Source" to 0x01, "Mute" to 0x0F, "Vol +" to 0x07, "Vol -" to 0x0B,
                "Ch +" to 0x12, "Ch -" to 0x10, "Up" to 0x60, "Down" to 0x61, "Left" to 0x65, "Right" to 0x62,
                "OK" to 0x68, "Back" to 0x58, "Menu" to 0x1A
            ) + digits { intArrayOf(0x11, 0x04, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E)[it] }
        ),
        Preset(
            "TV", "LG", "NEC", 0x04,
            listOf(
                "Power" to 0x08, "Source" to 0x0B, "Mute" to 0x09, "Vol +" to 0x02, "Vol -" to 0x03,
                "Ch +" to 0x00, "Ch -" to 0x01, "Up" to 0x40, "Down" to 0x41, "Left" to 0x07, "Right" to 0x06,
                "OK" to 0x44, "Back" to 0x28, "Menu" to 0x43, "Home" to 0x7C
            ) + digits { 0x10 + it }
        ),
        Preset(
            "TV", "Sony", "SIRC", 0x01,
            listOf(
                "Power" to 0x15, "Source" to 0x25, "Mute" to 0x14, "Vol +" to 0x12, "Vol -" to 0x13,
                "Ch +" to 0x10, "Ch -" to 0x11, "Up" to 0x74, "Down" to 0x75, "Left" to 0x34, "Right" to 0x33,
                "OK" to 0x65, "Back" to 0x63, "Menu" to 0x60
            ) + digits { if (it == 0) 9 else it - 1 }
        ),
        Preset(
            "TV", "Philips (RC5)", "RC5", 0x00,
            listOf(
                "Power" to 0x0C, "Mute" to 0x0D, "Vol +" to 0x10, "Vol -" to 0x11, "Ch +" to 0x20, "Ch -" to 0x21
            ) + digits { it }
        )
    )
}
