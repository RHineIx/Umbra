package com.umbra.hooks.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Local vector drawing shared by the picker and the hook; no downloaded assets or recipes. */
class SpacebarIconRenderer(private val icon: SpacebarIcon) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = RectF(3f, 3f, 21f, 21f)
    private val path = Path().apply {
        when (icon) {
            SpacebarIcon.UMBRA -> {
                moveTo(5f, 4f)
                lineTo(5f, 14f)
                cubicTo(5f, 23f, 19f, 23f, 19f, 14f)
                lineTo(19f, 4f)
            }
            SpacebarIcon.STAR -> {
                repeat(10) { index ->
                    val angle = -PI / 2 + index * PI / 5
                    val radius = if (index % 2 == 0) 10.5f else 4.6f
                    val x = 12f + cos(angle).toFloat() * radius
                    val y = 12f + sin(angle).toFloat() * radius
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            SpacebarIcon.HEART -> {
                moveTo(12f, 21f)
                cubicTo(9f, 18.5f, 2f, 13f, 2f, 8f)
                cubicTo(2f, 2f, 9f, 1f, 12f, 6f)
                cubicTo(15f, 1f, 22f, 2f, 22f, 8f)
                cubicTo(22f, 13f, 15f, 18.5f, 12f, 21f)
                close()
            }
            SpacebarIcon.SPARKLE -> {
                diamond(10f, 13f, 9f)
                diamond(19f, 5f, 4f)
                diamond(20f, 20f, 2.5f)
            }
            else -> Unit
        }
    }

    fun draw(canvas: Canvas, bounds: RectF, tint: Int, opacity: Float = .6f) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        // Apply opacity to the complete glyph, so overlapping strokes do not become darker.
        val checkpoint = canvas.saveLayerAlpha(bounds, (opacity.coerceIn(0f, 1f) * 255f).roundToInt())
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeWidth = 4f
            paint.style = Paint.Style.STROKE
            if (icon == SpacebarIcon.GOOGLE || icon == SpacebarIcon.GOOGLE_COLORS) {
                fun segment(start: Float, sweep: Float, color: Int) {
                    paint.color = color
                    canvas.drawArc(circle, start, sweep, false, paint)
                }
                val colorful = icon == SpacebarIcon.GOOGLE_COLORS
                segment(218f, 103f, if (colorful) 0xFFEA4335.toInt() else tint)
                segment(142f, 76.5f, if (colorful) 0xFFFBBC05.toInt() else tint)
                segment(46f, 96.5f, if (colorful) 0xFF34A853.toInt() else tint)
                segment(0f, 46.5f, if (colorful) 0xFF4285F4.toInt() else tint)
                paint.color = if (colorful) 0xFF4285F4.toInt() else tint
                canvas.drawLine(12f, 12f, 23f, 12f, paint)
            } else {
                paint.color = tint
                if (icon == SpacebarIcon.UMBRA) {
                    paint.strokeCap = Paint.Cap.ROUND
                } else {
                    paint.style = Paint.Style.FILL
                }
                canvas.drawPath(path, paint)
            }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun Path.diamond(x: Float, y: Float, radius: Float) {
        moveTo(x, y - radius)
        lineTo(x + radius * .36f, y - radius * .36f)
        lineTo(x + radius, y)
        lineTo(x + radius * .36f, y + radius * .36f)
        lineTo(x, y + radius)
        lineTo(x - radius * .36f, y + radius * .36f)
        lineTo(x - radius, y)
        lineTo(x - radius * .36f, y - radius * .36f)
        close()
    }
}
