package com.umbra.hooks

import android.annotation.SuppressLint
import android.util.Log
import com.umbra.hooks.apps.GooglePlayHook
import com.umbra.hooks.apps.GboardHook
import com.umbra.hooks.apps.GrafikaHook
import com.umbra.hooks.apps.PydroidHook
import com.umbra.hooks.apps.ShowlyHook
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

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
    private val activeHooks = ConcurrentHashMap<String, AppHook>()

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName

        val factory = hooksFactory[packageName] ?: return

        val logsEnabled = PrefsManager.getHookInt(this, Constants.KEY_GLOBAL_LOGS, 0) == 1
        if (logsEnabled) {
            log(Log.INFO, TAG, "[DEBUG] Analyzing supported package load: $packageName")
        }

        val hook = factory()
        if (activeHooks.putIfAbsent(packageName, hook) != null) return
        try {

            if (logsEnabled) {
                log(Log.INFO, TAG, "[MATCH] Executing ${hook.javaClass.simpleName} for $packageName")
            }

            hook.handleLoadPackage(this, param)

            if (logsEnabled) {
                log(Log.INFO, TAG, "[SUCCESS] ${hook.javaClass.simpleName} attached successfully.")
            }
        } catch (t: Throwable) {
            activeHooks.remove(packageName, hook)
            runCatching { hook.detachAll() }
            if (logsEnabled) {
                log(Log.ERROR, TAG, "[FATAL] Exception occurred during module execution", t)
            }
        }
    }

    // Play Store uses the final package class loader, rather than the initial loader.
    private var googlePlayHook: GooglePlayHook? = null

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != GooglePlayHook.PACKAGE || googlePlayHook != null) return
        val hook = GooglePlayHook(this)
        googlePlayHook = hook
        try {
            hook.start(param.classLoader)
        } catch (t: Throwable) {
            hook.detachAll()
            googlePlayHook = null
            log(Log.ERROR, TAG, "Google Play initialization failed", t)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Detach all hooks safely before hot-reloading to prevent memory leaks from the old generation
        googlePlayHook?.detachAll()
        googlePlayHook = null
        activeHooks.values.forEach { runCatching { it.detachAll() } }
        activeHooks.clear()

        return true // Confirm and allow the hot reload process
    }
}
