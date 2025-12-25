package com.umbra.hooks.utils

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object ViewUtils {

    // Atomic hiding: Ensures the view takes absolutely zero space
    // and disrupts the layout as little as possible.
    fun nukeView(view: View) {
        try {
            // 1. Basic Visibility
            view.visibility = View.GONE
            
            // 2. Clear minimum dimensions which might force size even if GONE
            view.minimumWidth = 0
            view.minimumHeight = 0

            // 3. LayoutParams Manipulation
            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0
                
                // Clear margins to prevent "ghost" spacing
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                    params.marginStart = 0
                    params.marginEnd = 0
                    params.topMargin = 0
                    params.bottomMargin = 0
                }
                
                view.layoutParams = params
            }
            
            // 4. Disable click listeners to prevent accidental interaction with hidden views
            view.setOnClickListener(null)
            view.isClickable = false
            
        } catch (_: Throwable) {}
    }

    // Forces the View to measure as 0x0.
    // This is the ultimate fallback if layout params fail.
    fun hookOnMeasure(clazz: Class<*>, hookedClasses: HashSet<String>) {
        if (hookedClasses.contains(clazz.name)) return
        hookedClasses.add(clazz.name)

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
                            try {
                                // FIX: 'setMeasuredDimension' is protected, so we MUST use XposedHelpers to call it.
                                // Direct call view.setMeasuredDimension(0, 0) will fail at compile time.
                                XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                            } catch (_: Throwable) { }
                            
                            param.result = null // Skip original execution
                        }
                    }
                })
        } catch (_: Throwable) {}
    }
}
