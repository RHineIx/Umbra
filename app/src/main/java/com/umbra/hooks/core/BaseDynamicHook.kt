package com.umbra.hooks.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Core Abstract Hook Class optimized for LibXposed API 102.
 * Includes thread management, caching, and atomic hook detach mechanisms.
 */
abstract class BaseDynamicHook : AppHook {

    protected abstract val tag: String
    protected abstract val prefsCacheName: String
    protected abstract val successToastMessage: String

    @Volatile protected var logsEnabled = false
    protected lateinit var xposedModule: XposedModule
    protected lateinit var targetClassLoader: ClassLoader

    // API 102: Track hooks via HookHandle to safely detach them later (Hot Reload Support)
    private val activeHookHandles = mutableListOf<XposedInterface.HookHandle>()

    companion object {
        val sharedExecutor: ExecutorService by lazy {
            ThreadPoolExecutor(
                0, 1,
                60L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>()
            )
        }
    }

    protected fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, tag, message)
    }

    protected fun logError(message: String, t: Throwable? = null) {
        if (logsEnabled) {
            if (t != null) xposedModule.log(Log.ERROR, tag, message, t)
            else xposedModule.log(Log.ERROR, tag, message)
        }
    }

    /**
     * API 102: Registers a hook handle for safe teardown
     */
    protected fun registerHook(handle: XposedInterface.HookHandle?) {
        handle?.let { activeHookHandles.add(it) }
    }

    override fun detachAll() {
        if (activeHookHandles.isNotEmpty()) {
            logInfo("[TEARDOWN] Detaching ${activeHookHandles.size} active hooks safely.")
            activeHookHandles.forEach { handle ->
                try {
                    handle.unhook() // Unhooks atomically in API 102
                } catch (_: Throwable) {}
            }
            activeHookHandles.clear()
        }
    }

    override fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        this.xposedModule = module
        this.targetClassLoader = param.defaultClassLoader
        
        logsEnabled = PrefsManager.getHookInt(xposedModule, Constants.KEY_GLOBAL_LOGS, 0) == 1

        try {
            val appClass = targetClassLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)

            val attachHookHandle = module.hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = chain.args[0] as Context
                    logInfo("[INFO] Application.attach intercepted. Initializing Dynamic Engine...")
                    onAppAttached(context, targetClassLoader)
                } catch (t: Throwable) {
                    logError("[FATAL] Error occurred inside attach execution block", t)
                }
                return@intercept result
            }
            registerHook(attachHookHandle)
            
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Application.attach", t)
        }

        onPackageLoaded(targetClassLoader)
    }

    open fun onPackageLoaded(classLoader: ClassLoader) {}

    private fun onAppAttached(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(prefsCacheName, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }

        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt("app_version", -1))

        if (isSameVersion && hasValidCache(cachePrefs)) {
            logInfo("[CACHE] Valid version cache ($versionCode) found. Applying optimized offsets.")
            applyHooks(classLoader, cachePrefs)
        } else {
            logInfo("[UPDATE] App version updated or cache missing. Initiating DexKit background search...")
            sharedExecutor.submit {
                try {
                    performSearchAndCache(context, versionCode, cachePrefs)
                    logInfo("[SUCCESS] Background DexKit analysis finished.")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(context, successToastMessage, Toast.LENGTH_LONG).show()
                        } catch (e: Throwable) {
                            logError("[WARN] Context invalid for Toast display", e)
                        }
                    }
                } catch (t: Throwable) {
                    logError("[ERROR] Background DexKit analysis failed", t)
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

    private fun performSearchAndCache(context: Context, versionCode: Int, cachePrefs: SharedPreferences) {
        try {
            loadDexKitSafely()
        } catch (e: Throwable) {
            return
        }

        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                performSearchSynchronous(bridge, cachePrefs)
            }
            cachePrefs.edit().putInt("app_version", versionCode).apply()
        } catch (t: Throwable) {
            logError("[ERROR] DexKit parsing engine encountered an exception", t)
        }
    }

    protected abstract fun hasValidCache(cachePrefs: SharedPreferences): Boolean
    protected abstract fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences)
    protected abstract fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences)
}
