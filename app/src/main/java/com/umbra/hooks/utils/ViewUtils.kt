package com.umbra.hooks.utils

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object ViewUtils {

    /**
     * Completely hides a view by setting visibility to GONE,
     * removing dimensions, and clearing margins.
     */
    fun nukeView(view: View) {
        try {
            // Optimization: If already nuked, skip
            if (view.visibility == View.GONE && view.layoutParams?.height == 0) return

            // 1. Set Visibility
            view.visibility = View.GONE

            // 2. Clear Minimum Dimensions
            view.minimumWidth = 0
            view.minimumHeight = 0

            // 3. Adjust LayoutParams
            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0

                // Clear margins to prevent whitespace
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                    params.marginStart = 0
                    params.marginEnd = 0
                    params.topMargin = 0
                    params.bottomMargin = 0
                }
                view.layoutParams = params
            }

            // 4. Disable Interaction
            view.setOnClickListener(null)
            view.isClickable = false
            view.isFocusable = false

        } catch (_: Throwable) {
        }
    }

    /**
     * Forces the view to measure as 0x0.
     * Useful for persistent parents that refuse to collapse via LayoutParams.
     */
    fun hookOnMeasure(clazz: Class<*>, hookedClasses: MutableSet<String>) {
        val className = clazz.name
        if (hookedClasses.contains(className)) return
        hookedClasses.add(className)

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (view.visibility == View.GONE) {
                            // Force dimension set to 0,0
                            try {
                                XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                            } catch (_: Throwable) {
                            }
                            // Skip the original onMeasure
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }
}
