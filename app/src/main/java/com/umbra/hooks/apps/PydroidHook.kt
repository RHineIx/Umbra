package com.umbra.hooks.apps

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class PydroidHook : AppHook {
    override val targetPackages = setOf("ru.iiec.pydroid3")

    override fun isEnabled(prefs: XSharedPreferences): Boolean {
        return true
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        val prefs = XSharedPreferences("com.umbra.hooks", Constants.PREFS_FILE)
        prefs.makeWorldReadable()
        prefs.reload()

        val isPremiumEnabled = prefs.getBoolean(Constants.KEY_PYDROID_PREMIUM, true)
        val isNoAdsEnabled = prefs.getBoolean(Constants.KEY_PYDROID_NO_ADS, true)
        val isNoJumpEnabled = prefs.getBoolean(Constants.KEY_PYDROID_NO_JUMP, true)

        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as Context
                    val appClassLoader = context.classLoader

                    // 1. Premium Logic
                    if (isPremiumEnabled) {
                        val premiumClasses = arrayOf(
                            "qwe.qweqwe.texteditor.o0", "qwe.qweqwe.texteditor.p0",
                            "ba.f0", "z9.h0", "s4.G"
                        )
                        val truthMethods = arrayOf("u0", "W0", "Y0", "X0")

                        premiumClasses.forEach { className ->
                            try {
                                val clazz = XposedHelpers.findClass(className, appClassLoader)
                                truthMethods.forEach { methodName ->
                                    try {
                                        XposedHelpers.findAndHookMethod(clazz, methodName, object : XC_MethodHook() {
                                            override fun afterHookedMethod(p: MethodHookParam) {
                                                p.result = true
                                            }
                                        })
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    // 2. No Ads Logic (Includes hiding story_circle)
                    if (isNoAdsEnabled) {
                        val adClasses = arrayOf(
                            "qwe.qweqwe.texteditor.o0", "qwe.qweqwe.texteditor.p0",
                            "ba.f0", "z9.h0", "s4.G"
                        )
                        val adMethods = arrayOf("L1", "T1", "X1", "U1")

                        try {
                            val navViewClass = XposedHelpers.findClass("com.google.android.material.navigation.NavigationView", appClassLoader)
                            adClasses.forEach { className ->
                                try {
                                    val clazz = XposedHelpers.findClass(className, appClassLoader)
                                    adMethods.forEach { methodName ->
                                        try {
                                            XposedHelpers.findAndHookMethod(clazz, methodName, navViewClass, object : XC_MethodHook() {
                                                override fun beforeHookedMethod(p: MethodHookParam) {
                                                    p.result = null
                                                }
                                            })
                                        } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}

                        // Force hide the 'story_circle' element from UI
                        hideStoryCircle(appClassLoader)
                    }

                    // 3. No Jump/Redirect Logic
                    if (isNoJumpEnabled) {
                        val jumpClasses = arrayOf("ba.d", "u4.d", "qwe.qweqwe.texteditor.e1.f0.e", "qwe.qweqwe.texteditor.f1.f0.e", "da.d")
                        val jumpMethods = arrayOf("d", "f")
                        
                        jumpClasses.forEach { cls ->
                            try {
                                val clazz = XposedHelpers.findClass(cls, appClassLoader)
                                jumpMethods.forEach { mthd ->
                                    try {
                                        XposedHelpers.findAndHookMethod(clazz, mthd, object : XC_MethodHook() {
                                            override fun beforeHookedMethod(p: MethodHookParam) {
                                                p.result = null
                                            }
                                        })
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        )
    }

    private fun hideStoryCircle(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        try {
                            val resId = activity.resources.getIdentifier("story_circle", "id", activity.packageName)
                            if (resId != 0) {
                                val view = activity.findViewById<View>(resId)
                                if (view != null && view.visibility != View.GONE) {
                                    view.visibility = View.GONE
                                    val params = view.layoutParams
                                    if (params != null) {
                                        params.width = 0
                                        params.height = 0
                                        view.layoutParams = params
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}
