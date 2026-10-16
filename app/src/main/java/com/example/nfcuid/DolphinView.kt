package com.example.nfcuid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.view.View
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

// Plays the official Flipper dolphin animations (assets/dolphin from firmware 1.4.3):
// frames + meta.txt (frame order, passive/active cycles, speech bubbles) + manifest.txt
class DolphinView(context: Context) : View(context) {

    private class Bubble(val slot: Int, val x: Int, val y: Int, val text: String, val start: Int, val end: Int)

    private class Anim(
        val name: String, val w: Int, val h: Int,
        val passive: Int, val active: Int, val order: IntArray,
        val cycles: Int, val fps: Float, val duration: Int, val cooldown: Int,
        val bubbles: List<Bubble>
    )

    private class Entry(val name: String, val minB: Int, val maxB: Int, val minL: Int, val maxL: Int, val weight: Int)

    private val bg = Color.rgb(255, 130, 0)
    private val entries = ArrayList<Entry>()
    private var anim: Anim? = null
    private var frames: Array<Bitmap?> = emptyArray()

    private var animStart = 0L
    private var lastTick = 0L
    private var curIdx = 0
    private var cdFrame = 0
    private var inCooldown = true
    private var cooldownEnd = 0L
    private var eventUntil = 0L
    private var passiveSlot = -1
    private var activeSlot = -1

    private val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint().apply { style = Paint.Style.FILL; color = bg }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val textPaint = Paint().apply { color = Color.BLACK; typeface = Typeface.MONOSPACE }

    init {
        loadManifest()
        // an event animation left by the app (file saved or failed), otherwise a normal idle animation
        val prefs = context.getSharedPreferences("flipper", Context.MODE_PRIVATE)
        val ev = prefs.getString("dolphin_event", null)
        if (ev != null) {
            prefs.edit().remove("dolphin_event").apply()
            startAnim(ev, true)
        } else {
            pickNext()
        }
        setOnClickListener { pickNext() }   // tap the dolphin for another animation
    }

    private fun readAsset(path: String): String? =
        try { context.assets.open(path).bufferedReader().use { it.readText() } } catch (e: Exception) { null }

    private fun loadManifest() {
        val t = readAsset("dolphin/manifest.txt") ?: return
        val blocks = ArrayList<HashMap<String, String>>()
        for (raw in t.lines()) {
            val l = raw.trim()
            if (!l.contains(":")) continue
            val k = l.substringBefore(":").trim()
            val v = l.substringAfter(":").trim()
            if (k == "Name") blocks.add(hashMapOf("Name" to v))
            else if (blocks.isNotEmpty()) blocks.last()[k] = v
        }
        for (b in blocks) {
            entries.add(
                Entry(
                    b["Name"] ?: continue,
                    b["Min butthurt"]?.toIntOrNull() ?: 0,
                    b["Max butthurt"]?.toIntOrNull() ?: DolphinState.BUTTHURT_MAX,
                    b["Min level"]?.toIntOrNull() ?: 0,
                    b["Max level"]?.toIntOrNull() ?: 0,
                    b["Weight"]?.toIntOrNull() ?: 1
                )
            )
        }
    }

    private fun parseMeta(name: String, text: String): Anim? {
        val kv = HashMap<String, String>()
        val blocks = ArrayList<HashMap<String, String>>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (!l.contains(":")) continue
            val k = l.substringBefore(":").trim()
            val v = l.substringAfter(":").trim()
            if (k == "Slot") blocks.add(hashMapOf("Slot" to v))
            else if (blocks.isNotEmpty()) blocks.last()[k] = v
            else kv[k] = v
        }
        val w = kv["Width"]?.toIntOrNull() ?: return null
        val h = kv["Height"]?.toIntOrNull() ?: return null
        val passive = kv["Passive frames"]?.toIntOrNull() ?: 0
        val active = kv["Active frames"]?.toIntOrNull() ?: 0
        var order = (kv["Frames order"] ?: "").split(" ").mapNotNull { it.toIntOrNull() }.toIntArray()
        if (order.isEmpty()) order = IntArray(passive + active) { it }
        if (order.isEmpty()) return null
        val bubbles = blocks.map { b ->
            Bubble(
                b["Slot"]?.toIntOrNull() ?: 0,
                b["X"]?.toIntOrNull() ?: 0,
                b["Y"]?.toIntOrNull() ?: 0,
                (b["Text"] ?: "").replace("\\n", "\n"),
                b["StartFrame"]?.toIntOrNull() ?: 0,
                b["EndFrame"]?.toIntOrNull() ?: 0
            )
        }
        return Anim(
            name, w, h, passive, active, order,
            kv["Active cycles"]?.toIntOrNull() ?: 1,
            kv["Frame rate"]?.toFloatOrNull() ?: 2f,
            kv["Duration"]?.toIntOrNull() ?: 60,
            kv["Active cooldown"]?.toIntOrNull() ?: 5,
            bubbles
        )
    }

    private fun chooseSlot(a: Anim, passive: Boolean): Int {
        val slots = a.bubbles.filter { (it.start < a.passive) == passive }.map { it.slot }.distinct()
        return if (slots.isEmpty()) -1 else slots.random()
    }

    // internal animations of the firmware: low battery and missing storage replace the random idle choice
    private fun batteryLow(): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return level >= 0 && level * 100 / scale <= 10 && !plugged
    }

    private fun pickNext() {
        if (batteryLow() && anim?.name != "L1_BadBattery_128x47") {
            startAnim("L1_BadBattery_128x47")
            return
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED && anim?.name != "L1_NoSd_128x49") {
            startAnim("L1_NoSd_128x49")
            return
        }
        val xp = DolphinState.xp(context)
        val level = DolphinState.level(xp)
        val bh = DolphinState.butthurt(context)
        // low battery / no storage animations are shown only under those conditions, not at random
        val conditional = listOf("L1_BadBattery_128x47", "L1_NoSd_128x49")
        var cands = entries.filter { it.minL > 0 && it.name !in conditional && level in it.minL..it.maxL && bh in it.minB..it.maxB }
        if (cands.isEmpty()) cands = entries.filter { it.minL > 0 && it.name !in conditional }
        if (cands.isEmpty()) return
        val others = cands.filter { it.name != anim?.name }
        val pool = if (others.isEmpty()) cands else others
        var r = Random.nextInt(pool.sumOf { it.weight.coerceAtLeast(1) })
        var chosen = pool.last()
        for (e in pool) {
            r -= e.weight.coerceAtLeast(1)
            if (r < 0) { chosen = e; break }
        }
        startAnim(chosen.name)
    }

    private fun startAnim(name: String, event: Boolean = false) {
        val meta = readAsset("dolphin/$name/meta.txt") ?: return
        val a = parseMeta(name, meta) ?: return
        val n = (a.order.maxOrNull() ?: 0) + 1
        frames = Array(n) { i ->
            try { context.assets.open("dolphin/$name/frame_$i.png").use { BitmapFactory.decodeStream(it) } }
            catch (e: Exception) { null }
        }
        anim = a
        val now = SystemClock.uptimeMillis()
        animStart = now
        eventUntil = if (event) now + 5000L else 0L
        lastTick = now
        curIdx = 0
        cdFrame = 0
        inCooldown = true
        cooldownEnd = now + a.cooldown * 1000L
        passiveSlot = chooseSlot(a, true)
        activeSlot = -1
        invalidate()
    }

    private fun frameIndex(a: Anim): Int {
        val passive = a.passive.coerceAtLeast(1)
        return if (inCooldown) {
            a.order[cdFrame % min(passive, a.order.size)]
        } else if (curIdx < a.passive) {
            a.order[curIdx % a.order.size]
        } else {
            val act = a.active.coerceAtLeast(1)
            a.order[(a.passive + (curIdx - a.passive) % act) % a.order.size]
        }
    }

    private fun drawBubble(c: Canvas, b: Bubble, left: Float, top: Float, s: Float) {
        val fh = 8f * s
        textPaint.textSize = fh * 0.9f
        val lines = b.text.split("\n")
        var tw = 0f
        for (ln in lines) tw = maxOf(tw, textPaint.measureText(ln))
        val fw = tw + 8f * s
        val fhgt = fh * lines.size + 4f * s
        val x = left + b.x * s
        val y = top + b.y * s
        c.drawRect(x, y, x + fw, y + fhgt, fill)
        stroke.strokeWidth = s
        c.drawRect(x, y, x + fw, y + fhgt, stroke)
        for ((i, ln) in lines.withIndex()) {
            c.drawText(ln, x + 4f * s, y + fh * (i + 1) - s, textPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = anim ?: return
        val now = SystemClock.uptimeMillis()
        val tick = (1000f / a.fps.coerceAtLeast(0.5f)).toLong()

        if (now - lastTick >= tick) {
            lastTick = now
            if (inCooldown) {
                cdFrame++
                if (now >= cooldownEnd) {
                    inCooldown = false
                    curIdx = 0
                    activeSlot = chooseSlot(a, false)
                }
            } else {
                curIdx++
                if (curIdx >= a.passive + a.active * a.cycles) {
                    inCooldown = true
                    cdFrame = 0
                    cooldownEnd = now + a.cooldown * 1000L
                    passiveSlot = chooseSlot(a, true)
                }
            }
            val over = if (eventUntil > 0L) now >= eventUntil else now - animStart > min(a.duration, 120) * 1000L
            if (over) {
                pickNext()
                postInvalidateDelayed(30)
                return
            }
        }

        val s = floor(min(width / 128f, height / 64f)).coerceAtLeast(1f)
        val left = (width - 128f * s) / 2f
        val top = (height - 64f * s) / 2f

        val bmp = frames.getOrNull(frameIndex(a))
        if (bmp != null) {
            val dst = RectF(left, top + (64 - a.h) * s, left + a.w * s, top + 64f * s)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
        }

        val curFrame = if (inCooldown) cdFrame else curIdx
        for (b in a.bubbles) {
            val isPassive = b.start < a.passive
            val chosen = if (isPassive) passiveSlot else activeSlot
            if (b.slot == chosen && curFrame >= b.start && curFrame <= b.end) {
                drawBubble(canvas, b, left, top, s)
            }
        }
        postInvalidateDelayed(30)
    }
}
