package com.umbra.hooks.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/** The same drawing is used in the picker and on Gboard's spacebar. */
class SpacebarIconRenderer(
    private val icon: SpacebarIcon,
    private val customBitmap: Bitmap? = null,
    private val customTint: Boolean = false,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val circle = RectF(3f, 3f, 21f, 21f)
    private val imageBounds = RectF(0f, 0f, 24f, 24f)
    private var appliedTint: Int? = null
    private val path: Path? = when (icon) {
        SpacebarIcon.CODE -> svgPath(CODE_PATH, 16f)
        SpacebarIcon.MEMORY_HEART -> svgPath(MEMORY_HEART_PATH, 22f)
        else -> null
    }

    fun draw(canvas: Canvas, bounds: RectF, tint: Int, opacity: Float = .6f) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        if (icon == SpacebarIcon.CUSTOM && (customBitmap == null || customBitmap.isRecycled)) return
        // Group opacity keeps intersecting strokes at the same transparency.
        val checkpoint = canvas.saveLayerAlpha(bounds, (opacity.coerceIn(0f, 1f) * 255f).roundToInt())
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            when (icon) {
                SpacebarIcon.GOOGLE, SpacebarIcon.GOOGLE_COLORS -> drawGoogle(canvas, tint)
                SpacebarIcon.CUSTOM -> {
                    val color = if (customTint) tint else null
                    if (appliedTint != color) {
                        paint.colorFilter = color?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
                        appliedTint = color
                    }
                    canvas.drawBitmap(customBitmap!!, null, imageBounds, paint)
                }
                else -> {
                    paint.color = tint
                    paint.style = Paint.Style.FILL
                    path?.let { canvas.drawPath(it, paint) }
                }
            }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawGoogle(canvas: Canvas, tint: Int) {
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
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
    }

    private fun svgPath(data: String, viewport: Float): Path =
        requireNotNull(PathParser.createPathFromPathData(data)).apply {
            transform(Matrix().apply { setScale(24f / viewport, 24f / viewport) })
        }

    private companion object {
        // Exact paths and viewports from the supplied AtIconsCode.svg and MemoryHeart.svg.
        const val CODE_PATH = "M9.052 1.684a1 1 0 0 1 1.896.633l-4 12a1 1 0 0 1-1.896-.633zM3.293 4.293a1 1 0 1 1 1.414 1.414L2.414 8l2.293 2.293a1 1 0 1 1-1.414 1.414l-3-3a1 1 0 0 1 0-1.414zm8 0a1 1 0 0 1 1.414 0l3 3a1 1 0 0 1 0 1.414l-3 3a1 1 0 0 1-1.414-1.414L13.586 8l-2.293-2.293a1 1 0 0 1 0-1.414"
        const val MEMORY_HEART_PATH = "M12 20h-2v-1H9v-1H8v-1H7v-1H6v-1H5v-1H4v-1H3v-1H2v-2H1V5h1V4h1V3h1V2h5v1h1v1h2V3h1V2h5v1h1v1h1v1h1v5h-1v2h-1v1h-1v1h-1v1h-1v1h-1v1h-1v1h-1v1h-1zm-7-9v1h1v1h1v1h1v1h1v1h1v1h2v-1h1v-1h1v-1h1v-1h1v-1h1v-1h1V9h1V6h-1V5h-1V4h-3v1h-1v1h-1v1h-2V6H9V5H8V4H5v1H4v1H3v3h1v2z"
    }
}
