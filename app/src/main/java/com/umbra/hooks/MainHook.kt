package com.umbra.hooks

import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.core.AppHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    // Initialize hooks list
    private val hooks: List<AppHook> = listOf(
        GboardHook(),
        PydroidHook()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        
        // 1. Self-Hook for Activation Check (Allows the app to know it's active)
        if (packageName == "com.umbra.hooks") {
            hookSelf(lpparam)
            return
        }

        // 2. Dispatch Hooks safely
        // Improved: Iterate and catch errors individually to prevent cascade failures
        for (hook in hooks) {
            if (packageName in hook.targetPackages) {
                try {
                    XposedBridge.log("[UMBRA] Loading hook for: $packageName")
                    hook.onLoad(lpparam)
                } catch (t: Throwable) {
                    // Critical: Catching generic Throwable prevents app crashes on boot
                    XposedBridge.log("[UMBRA] FATAL ERROR in ${hook.javaClass.simpleName}: ${t.message}")
                    t.printStackTrace()
                }
            }
        }
    }

    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam) {
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
        } catch (_: Throwable) { 
            // Ignore self-hook errors
        }
    }
}
