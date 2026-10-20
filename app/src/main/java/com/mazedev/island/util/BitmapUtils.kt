package com.mazedev.island.util

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapUtils {

    /** Hardware bitmaps can't be read pixel by pixel, so copy them to software memory. */
    fun soft(b: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) {
            b.copy(Bitmap.Config.ARGB_8888, false)
        } else b

    /** Scales down so the longest side is at most [maxSide]. Always returns our own copy. */
    fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val b = soft(src)
        val scale = maxSide.toFloat() / max(b.width, b.height)
        if (scale >= 1f) return if (b === src) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val w = (b.width * scale).roundToInt().coerceAtLeast(1)
        val h = (b.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** A vivid accent color picked from the cover, used for the island's equalizer bars. */
    fun accent(b: Bitmap): Int {
        val px = Bitmap.createScaledBitmap(soft(b), 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        hsv[1] = max(hsv[1], 0.45f)
        hsv[2] = max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    /** Cheap, good-looking blur: shrink to ~96px, then run a few box-blur passes. */
    fun blur(src: Bitmap, radius: Int = 5, passes: Int = 3): Bitmap {
        val base = soft(src)
        val w = 96
        val h = (96f * base.height / base.width).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(base, w, h, true)
        val a = IntArray(w * h)
        small.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(passes) {
            boxH(a, b, w, h, radius)
            boxV(b, a, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var ca = 0; var cr = 0; var cg = 0; var cb = 0; var n = 0
                for (k in -r..r) {
                    val c = src[row + (x + k).coerceIn(0, w - 1)]
                    ca += c ushr 24; cr += (c shr 16) and 0xFF; cg += (c shr 8) and 0xFF; cb += c and 0xFF
                    n++
                }
                dst[row + x] = ((ca / n) shl 24) or ((cr / n) shl 16) or ((cg / n) shl 8) or (cb / n)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (x in 0 until w) {
            for (y in 0 until h) {
                var ca = 0; var cr = 0; var cg = 0; var cb = 0; var n = 0
                for (k in -r..r) {
                    val c = src[(y + k).coerceIn(0, h - 1) * w + x]
                    ca += c ushr 24; cr += (c shr 16) and 0xFF; cg += (c shr 8) and 0xFF; cb += c and 0xFF
                    n++
                }
                dst[y * w + x] = ((ca / n) shl 24) or ((cr / n) shl 16) or ((cg / n) shl 8) or (cb / n)
            }
        }
    }
}
