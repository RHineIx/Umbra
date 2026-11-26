package com.umbra.hooks.update

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Umbra-owned dialog using platform UI and system palette resources only. */
internal object UpdateDialog {
    private data class Palette(val surface: Int, val text: Int, val secondary: Int,
        val accent: Int, val onAccent: Int, val iconBg: Int, val success: Int)

    private fun palette(context: Context, dark: Boolean): Palette {
        fun system(id: Int, fallback: Int): Int =
            if (Build.VERSION.SDK_INT >= 31) runCatching { context.getColor(id) }.getOrDefault(fallback)
            else fallback
        return if (dark) Palette(
            system(android.R.color.system_neutral1_800, 0xFF242730.toInt()),
            system(android.R.color.system_neutral1_50, 0xFFE3E6EF.toInt()),
            system(android.R.color.system_neutral2_200, 0xFFC4CAD8.toInt()),
            system(android.R.color.system_accent1_200, 0xFFB4C6EA.toInt()),
            system(android.R.color.system_accent1_900, 0xFF223653.toInt()),
            system(android.R.color.system_accent1_700, 0xFF3D4F6E.toInt()),
            0xFF86EFAC.toInt()
        ) else Palette(
            system(android.R.color.system_neutral1_50, 0xFFEEF1FA.toInt()),
            system(android.R.color.system_neutral1_900, 0xFF222733.toInt()),
            system(android.R.color.system_neutral2_700, 0xFF505969.toInt()),
            system(android.R.color.system_accent1_700, 0xFF425B88.toInt()),
            Color.WHITE,
            system(android.R.color.system_accent1_100, 0xFFD5E2FA.toInt()),
            0xFF166534.toInt()
        )
    }

    fun show(activity: Activity, installed: String, update: AppUpdate, onUpdate: () -> Unit): Dialog {
        val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val context = ContextThemeWrapper(activity, if (dark)
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val colors = palette(context, dark)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        fun label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) =
            TextView(context).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(colors.surface, 28)
        }
        fun append(view: View, top: Int = 0, width: Int = LinearLayout.LayoutParams.MATCH_PARENT,
                   height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
            root.addView(view, LinearLayout.LayoutParams(width, height).apply {
                topMargin = dp(top)
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        append(UpdateGlyph(context, colors.accent, colors.iconBg), width = dp(48), height = dp(48))
        append(label("Umbra update available", 21f, colors.text, true), 16)
        val versions = SpannableStringBuilder("v" + installed.removePrefix("v") + "  →  ")
        val start = versions.length
        versions.append(update.tag)
        versions.setSpan(android.text.style.ForegroundColorSpan(colors.success), start, versions.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        versions.setSpan(StyleSpan(Typeface.BOLD), start, versions.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(label(versions, 14f, colors.secondary), 8)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun button(text: String, primary: Boolean, action: () -> Unit): Button = Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) colors.onAccent else colors.accent)
            minimumWidth = dp(80)
            minWidth = dp(80)
            minimumHeight = dp(48)
            minHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
                rounded(if (primary) colors.accent else Color.TRANSPARENT, 24), rounded(Color.WHITE, 24)
            )
            stateListAnimator = null
            setOnClickListener { action() }
        }
        append(button("View changelog ↗", false) { openRelease(activity, update) },
            top = 8, width = LinearLayout.LayoutParams.WRAP_CONTENT)
        buttons.addView(button("Ignore", false) { dialog.dismiss() })
        buttons.addView(button("Update ↓", true) {
            dialog.dismiss()
            onUpdate()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        append(buttons, 20)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.55f)
        }
        try {
            dialog.show()
            dialog.window?.setLayout(minOf(dp(380),
                (context.resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(180))),
                WindowManager.LayoutParams.WRAP_CONTENT)
        } catch (error: Throwable) {
            runCatching { dialog.dismiss() }
            throw error
        }
        return dialog
    }

    fun openRelease(activity: Activity, update: AppUpdate) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url))) }
    }

    private class UpdateGlyph(context: Context, private val tint: Int, private val bg: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path().apply {
            moveTo(32f, 41f); lineTo(32f, 17f)
            moveTo(22f, 27f); lineTo(32f, 17f); lineTo(42f, 27f)
            moveTo(18f, 43f); lineTo(18f, 48f); lineTo(46f, 48f); lineTo(46f, 43f)
        }
        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            canvas.scale(width / 64f, height / 64f)
            paint.style = Paint.Style.FILL
            paint.color = bg
            canvas.drawRoundRect(0f, 0f, 64f, 64f, 19f, 19f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = tint
            paint.strokeWidth = 3.6f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }
    }
}
