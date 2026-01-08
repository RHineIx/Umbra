package com.umbra.hooks

import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private val hooks: List<AppHook> = listOf(
        GboardHook(),
        PydroidHook()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Self-Hook for Activation Check
        // This allows the MainActivity to know if the module is actually running
        if (lpparam.packageName == "com.umbra.hooks") {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.umbra.hooks.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return true
                        }
                    }
                )
            } catch (_: Throwable) { }
            return
        }

        // 2. Load Preferences
        val prefs = XSharedPreferences("com.umbra.hooks", Constants.PREFS_FILE)
        try {
            prefs.makeWorldReadable()
        } catch (_: Throwable) { }
        prefs.reload()

        // 3. Dispatch App Hooks
        hooks.forEach { hook ->
            try {
                if (lpparam.packageName in hook.targetPackages && hook.isEnabled(prefs)) {
                    hook.onLoad(lpparam)
                }
            } catch (t: Throwable) {
                XposedBridge.log("[UMBRA] Hook failed for ${lpparam.packageName}: ${t.message}")
            }
        }
    }
}