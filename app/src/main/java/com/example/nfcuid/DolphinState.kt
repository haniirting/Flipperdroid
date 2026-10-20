package com.example.nfcuid

import android.content.Context

// XP / level logic taken from Flipper firmware 1.4.3 (dolphin_state.c):
// level 1 up to 300 XP, level 2 up to 1800 XP, level 3 above.
object DolphinState {
    private const val LEVEL2_THRESHOLD = 300
    private const val LEVEL3_THRESHOLD = 1800
    const val BUTTHURT_MAX = 14

    private fun prefs(c: Context) = c.getSharedPreferences("flipper", Context.MODE_PRIVATE)
    private fun today() = System.currentTimeMillis() / 86_400_000L

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
    }

    fun xp(c: Context): Int = prefs(c).getInt("xp", 0)

    fun level(xp: Int): Int = when {
        xp <= LEVEL2_THRESHOLD -> 1
        xp <= LEVEL3_THRESHOLD -> 2
        else -> 3
    }

    fun xpToLevelUp(xp: Int): Int = when {
        xp <= LEVEL2_THRESHOLD -> LEVEL2_THRESHOLD - xp + 1
        xp <= LEVEL3_THRESHOLD -> LEVEL3_THRESHOLD - xp + 1
        else -> 0
    }

    // Simplified: butthurt grows by 1 for every day without activity (max 14)
    fun butthurt(c: Context): Int {
        val p = prefs(c)
        val last = p.getLong("last_active_day", 0L)
        if (last == 0L) {
            p.edit().putLong("last_active_day", today()).apply()
            return 0
        }
        return (today() - last).toInt().coerceIn(0, BUTTHURT_MAX)
    }

    // Deed weights follow dolphin_deed.c; every app can give at most 20 XP per day (dolphin_deed_limits)
    private const val DAILY_LIMIT = 20
    private val apps = listOf("subghz", "rfid", "nfc", "ir", "ibutton", "badusb", "plugin")

    fun addDeed(c: Context, app: String, weight: Int) {
        val p = prefs(c)
        val day = today()
        if (p.getLong("limit_day", 0L) != day) {
            val e = p.edit().putLong("limit_day", day)
            for (a in apps) e.putInt("limit_$a", 0)
            e.apply()
        }
        val used = p.getInt("limit_$app", 0)
        val allowed = minOf(weight, DAILY_LIMIT - used).coerceAtLeast(0)
        if (allowed > 0) {
            p.edit().putInt("limit_$app", used + allowed).apply()
            addXp(c, allowed)
        } else {
            p.edit().putLong("last_active_day", day).apply()
        }
    }

    // private: XP is only given through addDeed (real actions with the firmware weights and daily limit)
    private fun addXp(c: Context, weight: Int) {
        val p = prefs(c)
        p.edit().putInt("xp", p.getInt("xp", 0) + weight).putLong("last_active_day", today()).apply()
    }
}
