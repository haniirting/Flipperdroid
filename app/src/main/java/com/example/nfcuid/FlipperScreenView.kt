package com.example.nfcuid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

// Shows the Flipper 128x64 screen. Frame data = 1024 bytes in u8g2 page format
// (byte index = page * 128 + x, bit 0 = top pixel of the page), same as the firmware framebuffer.
class FlipperScreenView(context: Context) : View(context) {

    private val orange = Color.rgb(255, 130, 0)
    private val bmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val px = IntArray(128 * 64)

    init { bmp.eraseColor(orange) }

    fun setFrame(data: ByteArray, orientation: Int) {
        if (data.size < 1024) return
        val flip = orientation == 1            // HORIZONTAL_FLIP
        for (x in 0 until 128) {
            for (page in 0 until 8) {
                val b = data[page * 128 + x].toInt() and 0xFF
                for (bit in 0 until 8) {
                    val y = page * 8 + bit
                    val on = ((b shr bit) and 1) == 1
                    val dx = if (flip) 127 - x else x
                    val dy = if (flip) 63 - y else y
                    px[dy * 128 + dx] = if (on) Color.BLACK else orange
                }
            }
        }
        bmp.setPixels(px, 0, 128, 0, 0, 128, 64)
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), paint)
    }
}
