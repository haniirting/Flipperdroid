package com.example.nfcuid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.floor
import kotlin.math.min

// The first-start slideshow from the firmware (assets/slideshow/first_start): tap to go to the next slide
class SlideView(context: Context, private val frames: List<Bitmap>, private val onDone: () -> Unit) : View(context) {

    private var idx = 0
    private val paint = Paint().apply { isFilterBitmap = false }
    private val hint = Paint().apply { color = Color.BLACK; textSize = 40f }

    init {
        setOnClickListener {
            idx++
            if (idx >= frames.size) onDone() else invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(255, 130, 0))
        val s = floor(min(width / 128f, height / 64f)).coerceAtLeast(1f)
        val left = (width - 128f * s) / 2f
        val top = (height - 64f * s) / 2f
        val b = frames[idx]
        canvas.drawBitmap(b, null, RectF(left, top, left + b.width * s, top + b.height * s), paint)
        canvas.drawText("tap to continue  ${idx + 1}/${frames.size}", 32f, height - 48f, hint)
    }
}
