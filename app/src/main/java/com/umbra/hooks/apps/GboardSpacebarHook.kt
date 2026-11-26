package com.umbra.hooks.apps

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.CustomSpacebarIcon
import com.umbra.hooks.utils.PrefsManager
import com.umbra.hooks.utils.SpacebarIcon
import com.umbra.hooks.utils.SpacebarIconRenderer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Optional visual feature; independent of clipboard discovery, flags and retention. */
internal class GboardSpacebarHook(private val module: XposedModule, private val logsEnabled: Boolean) {
    private val active = AtomicBoolean(true)
    private val matched = AtomicBoolean(false)
    private val missing = AtomicBoolean(false)
    private val unsupported = AtomicBoolean(false)
    private val resourceIds = WeakHashMap<Resources, Int>()
    private class ResourceMatch(resources: Resources, val id: Int) {
        val resource = WeakReference(resources)
    }
    @Volatile private var recentResource: ResourceMatch? = null
    private val hiddenLabels = WeakHashMap<View, Float>()

    fun install(): XposedInterface.HookHandle? {
        if (PrefsManager.getHookInt(module, Constants.KEY_GBOARD_SPACEBAR_ENABLED, 0) != 1) return null
        return try {
            val icon = SpacebarIcon.fromId(PrefsManager.getHookInt(module, Constants.KEY_GBOARD_SPACEBAR_ICON, SpacebarIcon.GOOGLE.id))
            val customBitmap = if (icon == SpacebarIcon.CUSTOM) {
                val image = module.getRemotePreferences(Constants.PREFS_FILE)
                    .getString(Constants.KEY_GBOARD_SPACEBAR_CUSTOM_IMAGE, null)
                CustomSpacebarIcon.decodeStored(image) ?: run {
                    log("[WARN] Custom icon is unavailable; leaving the original spacebar unchanged.")
                    return null
                }
            } else null
            val customTint = icon == SpacebarIcon.CUSTOM &&
                PrefsManager.getHookInt(module, Constants.KEY_GBOARD_SPACEBAR_CUSTOM_TINT, 0) == 1
            val renderer = SpacebarIconRenderer(icon, customBitmap, customTint)
            val draw = View::class.java.getDeclaredMethod("draw", Canvas::class.java)
            val handle = module.hook(draw).intercept { chain ->
                val view = chain.thisObject as? View
                val canvas = chain.args.firstOrNull() as? Canvas
                var tint: Int? = null
                if (active.get() && view != null && canvas != null && view.id != View.NO_ID) {
                    try {
                        if (view.width > 0 && view.height > 0 && isSpacebar(view)) tint = prepareLabels(view)
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }
                // Always preserve Gboard's original drawing, background, touch and accessibility behavior.
                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    if (tint != null) restoreLabels()
                    throw t
                }
                if (active.get() && tint != null && view != null && canvas != null) {
                    try {
                        val bounds = iconBounds(view)
                        renderer.draw(canvas, bounds, tint)
                        if (matched.compareAndSet(false, true)) {
                            log("[MATCH] Spacebar view found; drawing ${icon.name} at 22dp / 60%.")
                        }
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }
                result
            }
            log("[SUCCESS] Spacebar icon hook installed (${icon.name}); awaiting key_pos_space.")
            object : XposedInterface.HookHandle by handle {
                override fun unhook() {
                    active.set(false)
                    try { handle.unhook() } finally { restoreLabels() }
                }
            }
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    private fun isSpacebar(view: View): Boolean {
        val resources = view.resources
        val recent = recentResource
        val id = if (recent != null && recent.resource.get() === resources) recent.id else synchronized(resourceIds) {
            resourceIds.getOrPut(resources) {
                resources.getIdentifier("key_pos_space", "id", "com.google.android.inputmethod.latin")
            }.also { recentResource = ResourceMatch(resources, it) }
        }
        if (id == 0 && missing.compareAndSet(false, true)) {
            log("[WARN] key_pos_space is unavailable in this resource set; leaving its views unchanged.")
        }
        return id != 0 && view.id == id
    }

    private fun prepareLabels(root: View): Int? {
        if (root !is ViewGroup) {
            if (unsupported.compareAndSet(false, true)) log("[WARN] Unsupported spacebar view type; leaving the original key unchanged.")
            return null
        }
        val queue = ArrayDeque<View>()
        for (index in 0 until root.childCount) queue.add(root.getChildAt(index))
        val labels = mutableListOf<TextView>()
        var scanned = 0
        var tint: Int? = null
        // First collect, then mutate. A changed or unexpectedly large view
        // hierarchy must not leave partially hidden labels.
        while (queue.isNotEmpty() && scanned++ < 64) {
            val view = queue.removeFirst()
            if (view.visibility != View.VISIBLE) continue
            if (view is TextView && !view.text.isNullOrEmpty()) {
                if (tint == null && Color.alpha(view.currentTextColor) > 0) tint = view.currentTextColor
                labels.add(view)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        if (queue.isNotEmpty()) {
            restoreLabels()
            if (unsupported.compareAndSet(false, true)) log("[WARN] Spacebar hierarchy exceeded scan limit; original labels retained.")
            return null
        }
        for (view in labels) {
            if (view.alpha != 0f) {
                synchronized(hiddenLabels) { if (!hiddenLabels.containsKey(view)) hiddenLabels[view] = view.alpha }
                view.alpha = 0f
            }
        }
        return tint ?: Color.GRAY
    }

    private fun iconBounds(view: View): RectF {
        // Gboard can give the key a larger touch/layout area than its visible background.
        // Padding normally includes background insets; max avoids counting them twice.
        val optical = if (Build.VERSION.SDK_INT >= 29) view.background?.opticalInsets else null
        val left = maxOf(view.paddingLeft, optical?.left ?: 0).coerceIn(0, view.width).toFloat()
        val top = maxOf(view.paddingTop, optical?.top ?: 0).coerceIn(0, view.height).toFloat()
        val right = (view.width - maxOf(view.paddingRight, optical?.right ?: 0)).coerceIn(0, view.width).toFloat()
        val bottom = (view.height - maxOf(view.paddingBottom, optical?.bottom ?: 0)).coerceIn(0, view.height).toFloat()
        val valid = right > left && bottom > top
        val x1 = if (valid) left else 0f
        val y1 = if (valid) top else 0f
        val x2 = if (valid) right else view.width.toFloat()
        val y2 = if (valid) bottom else view.height.toFloat()
        val size = minOf(22f * view.resources.displayMetrics.density, x2 - x1, y2 - y1)
        val centerX = (x1 + x2) * .5f
        val centerY = (y1 + y2) * .5f
        return RectF(centerX - size / 2f, centerY - size / 2f,
            centerX + size / 2f, centerY + size / 2f)
    }

    private fun restoreLabels() {
        val labels = synchronized(hiddenLabels) {
            hiddenLabels.entries.map { it.key to it.value }.also { hiddenLabels.clear() }
        }
        labels.forEach { (view, alpha) ->
            val restore = Runnable {
                if (view.alpha == 0f) view.alpha = alpha
                view.invalidate()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) restore.run() else Handler(Looper.getMainLooper()).post(restore)
        }
    }

    private fun fail(error: Throwable) {
        active.set(false)
        restoreLabels()
        if (logsEnabled) module.log(Log.ERROR, TAG, "[ERROR] Spacebar icon disabled for this process; original labels restored.", error)
    }

    private fun log(message: String) {
        if (logsEnabled) module.log(Log.INFO, TAG, message)
    }

    private companion object { const val TAG = "UMBRA-SPACEBAR" }
}
