package com.example.nfcuid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable

// Loads Flipper UI icons (assets/icons, converted from the firmware assets/icons) as tinted, scaled drawables.
object IconUtil {
    fun drawable(ctx: Context, dir: String, tint: Int, scale: Int = 3): BitmapDrawable? {
        return try {
            val name = ctx.assets.list("icons/$dir")?.sorted()?.firstOrNull { it.endsWith(".png") } ?: return null
            val bmp = ctx.assets.open("icons/$dir/$name").use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, bmp.width * scale, bmp.height * scale, false)
            val d = BitmapDrawable(ctx.resources, scaled)
            d.setBounds(0, 0, scaled.width, scaled.height)
            d.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            d
        } catch (e: Exception) {
            null
        }
    }
}
