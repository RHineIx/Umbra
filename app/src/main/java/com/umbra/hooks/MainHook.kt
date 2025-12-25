package com.umbra.hooks

import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.PinterestHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 1. Pinterest Hook
        if (lpparam.packageName == "com.pinterest") {
            try {
                PinterestHook().init(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("[UMBRA] Failed to load PinterestHook: $t")
            }
        }

        // 2. Gboard Hook
        if (lpparam.packageName == "com.google.android.inputmethod.latin") {
            try {
                GboardHook().init(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("[UMBRA] Failed to load GboardHook: $t")
            }
        }
    }
}