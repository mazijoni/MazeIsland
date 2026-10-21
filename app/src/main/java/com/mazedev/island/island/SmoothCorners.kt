package com.mazedev.island.island

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Rounded rectangle with "continuous" (squircle-style, like iOS) corners: the curve eases out of
 * the straight edges instead of starting abruptly like a circular arc, so the bevel-to-straight
 * transition is smooth. [smoothing] 0 = normal round corners, 0.6 ≈ iOS.
 */
class SmoothCornerShape(private val radius: Dp, private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }.coerceAtLeast(0f)
        val half = minOf(size.width, size.height) / 2f
        // How far along each edge the corner curve reaches. A pill (r = half) stays a perfect pill.
        val reach = ((1f + smoothing) * r).coerceAtMost(half).coerceAtLeast(0f)
        if (reach <= 0.5f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        val effSmooth = (reach / r.coerceAtLeast(0.001f) - 1f).coerceIn(0f, smoothing)
        // Control points placed so the corner's middle sits where a plain arc of radius r would be,
        // while the ends blend tangentially into the edges.
        val a = ((8f * 0.2929f / (1f + effSmooth)) - 1f) / 3f
        val c = reach * a.coerceIn(0.05f, 0.45f)
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(reach, 0f)
            lineTo(w - reach, 0f)
            cubicTo(w - c, 0f, w, c, w, reach)
            lineTo(w, h - reach)
            cubicTo(w, h - c, w - c, h, w - reach, h)
            lineTo(reach, h)
            cubicTo(c, h, 0f, h - c, 0f, h - reach)
            lineTo(0f, reach)
            cubicTo(0f, c, c, 0f, reach, 0f)
            close()
        }
        return Outline.Generic(p)
    }

    override fun equals(other: Any?) = other is SmoothCornerShape && other.radius == radius && other.smoothing == smoothing
    override fun hashCode() = radius.hashCode() * 31 + smoothing.hashCode()
}

/** Where the corner curve of a [SmoothCornerShape] meets the straight edge, in dp. */
fun smoothCornerReach(radiusDp: Float, smoothing: Float = 0.6f) = (1f + smoothing) * radiusDp
