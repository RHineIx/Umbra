package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.util.concurrent.*

@SuppressLint("NewApi")
class ShowlyHook : AppHook {

    companion object {
        private const val TAG = "UMBRA-SHOWLY"
        private const val PREFS_CACHE = "umbra_showly_cache"
        private const val KEY_VERSION = "showly_version"
        private const val KEY_PREMIUM_TARGETS = "showly_premium_targets"
        private const val KEY_SYNC_TARGETS = "showly_sync_targets"
    }

    override val targetPackages = setOf("com.michaldrabik.showly2")

    @Volatile private var logsEnabled = false
    private lateinit var xposedModule: XposedModule

    private val backgroundExecutor: ExecutorService = ThreadPoolExecutor(
        0, 1,
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()
    )

    private fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (logsEnabled) {
            if (t != null) xposedModule.log(Log.ERROR, TAG, message, t)
            else xposedModule.log(Log.ERROR, TAG, message)
        }
    }

    override fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        this.xposedModule = module
        val classLoader = param.defaultClassLoader

        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)

            module.hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = chain.args[0] as Context
                    logsEnabled = PrefsManager.getHookInt(xposedModule, Constants.KEY_GLOBAL_LOGS, 0) == 1
                    logInfo("[INFO] Showly Application.attach intercepted. Initializing Dynamic Hooks...")

                    handleDynamicHooks(context, classLoader)
                } catch (t: Throwable) {
                    logError("[FATAL] Error occurred inside attach execution block", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Application.attach", t)
        }
    }

    private fun handleDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }

        val cachedPremiumTargets = cachePrefs.getStringSet(KEY_PREMIUM_TARGETS, null)
        val cachedSyncTargets = cachePrefs.getStringSet(KEY_SYNC_TARGETS, null)
        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        if (isSameVersion && cachedPremiumTargets != null && cachedSyncTargets != null) {
            logInfo("[CACHE] Valid version cache ($versionCode) found. Applying optimized offsets.")
            applyHooks(classLoader, cachedPremiumTargets, cachedSyncTargets)
        } else {
            logInfo("[UPDATE] App version updated or cache missing. Initiating DexKit background search...")
            backgroundExecutor.submit {
                try {
                    performSearchAndHookSynchronous(context, classLoader, versionCode, cachePrefs)
                    logInfo("[SUCCESS] Background DexKit analysis finished.")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(context, "Umbra: Showly optimized & patched. Please restart.", Toast.LENGTH_LONG).show()
                        } catch (e: Throwable) {
                            logError("[WARN] Context invalid for Toast display", e)
                        }
                    }
                } catch (t: Throwable) {
                    logError("[ERROR] Background DexKit analysis failed", t)
                } finally {
                    backgroundExecutor.shutdown()
                }
            }
        }
    }

    private fun loadDexKitSafely() {
        try {
            System.loadLibrary("dexkit")
        } catch (e: UnsatisfiedLinkError) {
            logInfo("[WARN] Native load failed. Invoking absolute path extraction.")
            try {
                val appInfoMethod = xposedModule.javaClass.methods.firstOrNull {
                    it.name == "getApplicationInfo" || it.name == "getModuleApplicationInfo"
                }
                val appInfo = appInfoMethod?.invoke(xposedModule) as? android.content.pm.ApplicationInfo

                if (appInfo != null) {
                    val libPath = "${appInfo.nativeLibraryDir}/libdexkit.so"
                    System.load(libPath)
                    logInfo("[SUCCESS] libdexkit.so injected from absolute path.")
                } else {
                    throw UnsatisfiedLinkError("Could not resolve module ApplicationInfo")
                }
            } catch (e2: Throwable) {
                logError("[FATAL] Complete failure loading DexKit.", e2)
                throw e2
            }
        }
    }

    private fun performSearchAndHookSynchronous(context: Context, classLoader: ClassLoader, versionCode: Int, cachePrefs: SharedPreferences) {
        try {
            loadDexKitSafely()
        } catch (e: Throwable) {
            return
        }

        val premiumTargets = mutableSetOf<String>()
        val syncTargets = mutableSetOf<String>()

        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                
                // 1. Locate the local premium verification class
                bridge.findClass {
                    matcher {
                        usingStrings("KEY_PREMIUM")
                    }
                }.forEach { c ->
                    premiumTargets.add(c.name)
                    logInfo("[SEARCH] Found Premium Target: ${c.name}")
                }

                // 2. Locate the background sync method preventing the restart loop
                bridge.findMethod {
                    matcher {
                        name("onStart")
                        usingStrings("settingsRepository")
                    }
                }.forEach { m ->
                    syncTargets.add("${m.className}|${m.methodName}")
                    logInfo("[SEARCH] Found Lifecycle Sync Method: ${m.className}.${m.methodName}")
                }
            }

            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putStringSet(KEY_PREMIUM_TARGETS, premiumTargets)
                .putStringSet(KEY_SYNC_TARGETS, syncTargets)
                .apply()

            applyHooks(classLoader, premiumTargets, syncTargets)

        } catch (t: Throwable) {
            logError("[ERROR] DexKit parsing engine encountered an exception", t)
        }
    }

    private fun applyHooks(classLoader: ClassLoader, premiumClasses: Set<String>, syncTargets: Set<String>) {
        
        // Apply Premium Local Bypass
        var hookedPremiumMethods = 0
        for (className in premiumClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { m ->
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty() && !Modifier.isAbstract(m.modifiers)) {
                        try {
                            xposedModule.hook(m).intercept { true }
                            hookedPremiumMethods++
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                logError("[WARN] Failed to hook premium class: $className", e)
            }
        }
        logInfo("[SUCCESS] Aggressive Premium state forced. Hooked $hookedPremiumMethods methods.")

        // Apply Lifecycle Sync Bypass
        for (target in syncTargets) {
            val parts = target.split("|")
            if (parts.size == 2) {
                try {
                    val clazz = classLoader.loadClass(parts[0])
                    clazz.declaredMethods.forEach { m ->
                        if (m.name == parts[1]) {
                            // Intercept and return void (null) to drop the server call
                            xposedModule.hook(m).intercept { null }
                            logInfo("[SUCCESS] Lifecycle Sync execution neutralized for ${parts[0]}")
                        }
                    }
                } catch (e: Exception) {
                    logError("[WARN] Failed to neutralize sync on ${parts[0]}", e)
                }
            }
        }
    }
}