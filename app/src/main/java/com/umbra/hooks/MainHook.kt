package com.umbra.hooks

import android.annotation.SuppressLint
import android.util.Log
import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@SuppressLint("NewApi")
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "UMBRA-MAIN"
    }

    private val hooks: List<AppHook> = listOf(
        GboardHook(),
        PydroidHook()
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName

        val logsEnabled = PrefsManager.getHookInt(this, Constants.KEY_GLOBAL_LOGS, 0) == 1

        if (logsEnabled) {
            log(Log.INFO, TAG, "[DEBUG] Analyzing package load: $packageName")
        }

        for (hook in hooks) {
            if (packageName in hook.targetPackages) {
                try {
                    if (logsEnabled) {
                        log(Log.INFO, TAG, "[MATCH] Target package found: $packageName. Executing ${hook.javaClass.simpleName}")
                    }
                    hook.handleLoadPackage(this, param)
                    if (logsEnabled) {
                        log(Log.INFO, TAG, "[SUCCESS] ${hook.javaClass.simpleName} executed successfully for $packageName")
                    }
                } catch (t: Throwable) {
                    if (logsEnabled) {
                        log(Log.ERROR, TAG, "[FATAL] Exception occurred in ${hook.javaClass.simpleName}", t)
                    }
                }
            }
        }
    }
}