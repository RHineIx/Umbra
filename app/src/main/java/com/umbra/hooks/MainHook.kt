package com.umbra.hooks

import com.umbra.hooks.apps.PinterestHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // Pinterest Hook
        if (lpparam.packageName == "com.pinterest") {
            try {
                PinterestHook().init(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("[UMBRA] Failed to load PinterestHook: $t")
            }
        }
    }
}