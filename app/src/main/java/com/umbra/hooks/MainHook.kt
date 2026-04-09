package com.umbra.hooks

import android.annotation.SuppressLint
import android.util.Log
import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.core.AppHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@SuppressLint("NewApi")
class MainHook : XposedModule() {

    private val hooks: List<AppHook> = listOf(
        GboardHook(),
        PydroidHook()
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName

        for (hook in hooks) {
            if (packageName in hook.targetPackages) {
                try {
                    log(Log.INFO, "UMBRA", "Loading hook for: $packageName")
                    hook.handleLoadPackage(this, param)
                } catch (t: Throwable) {
                    log(Log.ERROR, "UMBRA", "FATAL ERROR in ${hook.javaClass.simpleName}", t)
                }
            }
        }
    }
}