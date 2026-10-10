package com.example.nfcuid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

// Simple original pixel-art dolphin that bobs up and down
class DolphinView(context: Context) : View(context) {

    private val art = listOf(
        ".........##.............",
        "........###.............",
        "...#...#############....",
        "..###.################..",
        ".##########E###########.",
        "...##################.##",
        "....################.#..",
        ".....###########........",
        "........####..###.......",
        "........##......##......"
    )
    private val cols = 24
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rows = art.size
        val px = minOf(width / cols, height / (rows + 2)).coerceAtLeast(1)
        val bob = (Math.sin(System.currentTimeMillis() / 400.0) * px).toInt()
        val offX = (width - cols * px) / 2
        val offY = (height - rows * px) / 2 + bob
        for ((y, row) in art.withIndex()) {
            for (x in 0 until cols) {
                val ch = row.getOrElse(x) { '.' }
                paint.color = when (ch) {
                    '#' -> Color.BLACK
                    'E' -> Color.rgb(255, 130, 0)
                    else -> continue
                }
                val l = (offX + x * px).toFloat()
                val t = (offY + y * px).toFloat()
                canvas.drawRect(l, t, l + px, t + px, paint)
            }
        }
        postInvalidateOnAnimation()
    }
}
