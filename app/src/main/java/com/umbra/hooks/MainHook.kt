package com.umbra.hooks

import android.annotation.SuppressLint
import android.util.Log
import com.umbra.hooks.apps.GooglePlayHook
import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.GrafikaHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.apps.ShowlyHook
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.update.InjectedUpdateHook
import com.umbra.hooks.update.UpdateProvider
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@SuppressLint("NewApi")
class MainHook : XposedModule() {
    companion object {
        private const val TAG = "UMBRA-MAIN"
    }

    // Lazy initialization mapping
    private val hooksFactory: Map<String, () -> AppHook> =
        mapOf(
            "com.google.android.inputmethod.latin" to { GboardHook() },
            "ru.iiec.pydroid3" to { PydroidHook() },
            "org.picquantmedia.grafika" to { GrafikaHook() },
            "com.michaldrabik.showly2" to { ShowlyHook() },
        )

    // API 102: Tracking active module instances for hot-reload scenarios securely
    private val lifecycleLock = Any()
    private val activeHooks = mutableMapOf<String, AppHook>()
    private var retired = false
    private val updateDialogs = mutableMapOf<String, InjectedUpdateHook>()

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName

        val factory = hooksFactory[packageName] ?: return

        val logsEnabled = PrefsManager.getHookInt(this, Constants.KEY_GLOBAL_LOGS, 0) == 1
        if (logsEnabled) {
            log(Log.INFO, TAG, "[DEBUG] Analyzing supported package load: $packageName")
        }

        synchronized(lifecycleLock) {
            if (retired || activeHooks.containsKey(packageName)) return
            if (packageName in UpdateProvider.TARGETS && packageName !in updateDialogs) {
                runCatching { InjectedUpdateHook(this, packageName).also { it.install() } }
                    .onSuccess { updateDialogs[packageName] = it }
                    .onFailure { log(Log.ERROR, "UMBRA-UPDATE", "[$packageName] Lifecycle hook installation failed", it) }
            }
            var hook: AppHook? = null
            try {
                val instance = factory()
                hook = instance
                activeHooks[packageName] = instance
                if (logsEnabled) {
                    log(Log.INFO, TAG, "[MATCH] Executing " + instance.javaClass.simpleName + " for " + packageName)
                }
                instance.handleLoadPackage(this, param)
                if (logsEnabled) {
                    log(Log.INFO, TAG, "[SUCCESS] " + instance.javaClass.simpleName + " attached successfully.")
                }
            } catch (t: Throwable) {
                activeHooks.remove(packageName)
                runCatching { hook?.detachAll() }
                if (logsEnabled) log(Log.ERROR, TAG, "[FATAL] Exception occurred during module execution", t)
            }
        }
    }

    // Play Store uses the final package class loader, rather than the initial loader.
    private var googlePlayHook: GooglePlayHook? = null

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != GooglePlayHook.PACKAGE) return
        synchronized(lifecycleLock) {
            if (retired || googlePlayHook != null) return
            val hook = GooglePlayHook(this)
            googlePlayHook = hook
            try {
                hook.start(param.classLoader)
            } catch (t: Throwable) {
                runCatching { hook.detachAll() }
                googlePlayHook = null
                log(Log.ERROR, TAG, "Google Play initialization failed", t)
            }
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Retire this generation before draining handles. New package callbacks
        // cannot race with teardown and repopulate the old registry.
        synchronized(lifecycleLock) {
            retired = true
            runCatching { googlePlayHook?.detachAll() }
            googlePlayHook = null
            activeHooks.values.forEach { runCatching { it.detachAll() } }
            activeHooks.clear()
            updateDialogs.values.forEach { runCatching { it.detach() } }
            updateDialogs.clear()
        }

        return true // Confirm and allow the hot reload process
    }
}
