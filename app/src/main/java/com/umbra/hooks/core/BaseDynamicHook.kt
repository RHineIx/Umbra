package com.umbra.hooks.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared lifecycle and exact-build cache for background-discovered hooks.
 * Gboard overrides initialization to preserve its early synchronous discovery.
 * Target-specific matchers and hook callbacks remain the responsibility of adapters.
 */
abstract class BaseDynamicHook : AppHook {
    protected abstract val tag: String
    protected abstract val prefsCacheName: String
    protected abstract val successToastMessage: String
    protected open val discoveryRulesVersion: Int = 1
    // Deferred adapters can legitimately install no handles during discovery.
    // A discovery result must not be reported as installed in that case.
    protected open val mayDeferInstallation: Boolean = false

    @Volatile protected var logsEnabled = false
    protected lateinit var xposedModule: XposedModule
    protected lateinit var targetClassLoader: ClassLoader

    private val lifecycle = Any()
    private val activeHookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val attemptHandles = ThreadLocal<MutableList<XposedInterface.HookHandle>>()
    private val started = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)
    @Volatile private var detached = false
    private var worker: ExecutorService? = null
    private var task: Future<*>? = null

    protected fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, tag, message)
    }

    protected fun logError(message: String, t: Throwable? = null) {
        if (logsEnabled) {
            if (t != null) xposedModule.log(Log.ERROR, tag, message, t)
            else xposedModule.log(Log.ERROR, tag, message)
        }
    }

    /** A late registration from a cancelled scan is immediately unhooked. */
    protected fun registerHook(handle: XposedInterface.HookHandle?) {
        if (handle == null) return
        val accepted = synchronized(lifecycle) {
            if (detached) false else {
                activeHookHandles.add(handle)
                attemptHandles.get()?.add(handle)
                true
            }
        }
        if (!accepted) unhook(handle)
    }

    private fun unhook(handle: XposedInterface.HookHandle) {
        try { handle.unhook() } catch (_: Throwable) {}
    }

    override fun detachAll() {
        val handles = synchronized(lifecycle) {
            detached = true
            task?.cancel(true)
            worker?.shutdownNow()
            task = null
            worker = null
            activeHookHandles.toList().asReversed().also { activeHookHandles.clear() }
        }
        handles.forEach(::unhook)
        logInfo("[TEARDOWN] Detached " + handles.size + " hooks and cancelled discovery.")
    }

    override fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (detached || param.packageName !in targetPackages || !started.compareAndSet(false, true)) return
        xposedModule = module
        targetClassLoader = param.defaultClassLoader
        logsEnabled = PrefsManager.getHookInt(module, Constants.KEY_GLOBAL_LOGS, 0) == 1

        try {
            val attach = android.app.Application::class.java.getDeclaredMethod("attach", Context::class.java)
            registerHook(module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = chain.args.firstOrNull() as? Context
                    if (!detached && context != null && context.packageName in targetPackages &&
                        attached.compareAndSet(false, true)) {
                        targetClassLoader = context.classLoader
                        onAppAttached(context)
                    }
                } catch (t: Throwable) {
                    logError("[ERROR] Dynamic initialization failed.", t)
                }
                result
            })
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Application.attach", t)
        }
        if (!detached) onPackageLoaded(targetClassLoader)
    }

    open fun onPackageLoaded(classLoader: ClassLoader) {}

    private fun identity(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val app = context.applicationInfo
        DynamicCacheRules.buildKey(context.packageName, code, info.versionName, info.lastUpdateTime,
            DynamicCacheRules.sha256(File(app.sourceDir)),
            app.splitSourceDirs.orEmpty().map { DynamicCacheRules.sha256(File(it)) },
            discoveryRulesVersion)
    }.getOrNull()

    private fun hasCandidates(prefs: SharedPreferences): Boolean =
        hasValidCache(prefs) && DynamicCacheRules.hasTargets(prefs.all)

    /**
     * Track only handles created on this installation thread. Existing lifecycle
     * hooks and independently deferred callbacks are never part of its rollback.
     */
    private fun attempt(block: () -> Unit): Int? {
        if (detached) return null
        val batch = mutableListOf<XposedInterface.HookHandle>()
        attemptHandles.set(batch)
        return try {
            block()
            if (detached) null else batch.size
        } catch (t: Throwable) {
            val rollback = synchronized(lifecycle) {
                batch.asReversed().filter { activeHookHandles.remove(it) }
            }
            rollback.forEach(::unhook)
            logError("[ERROR] Installation attempt failed; its registered handles were rolled back.", t)
            null
        } finally {
            attemptHandles.remove()
        }
    }

    private fun onAppAttached(context: Context) {
        val prefs = context.getSharedPreferences(prefsCacheName, Context.MODE_PRIVATE)
        // Compute content identity once on attach to preserve the previous
        // synchronous cache-hit installation timing. Fresh scanning is asynchronous.
        val key = identity(context)
        if (detached) return
        if (key != null && prefs.getString(DynamicCacheRules.BUILD_KEY, null) == key && hasCandidates(prefs)) {
            val count = attempt { applyHooks(targetClassLoader, prefs) }
            if (count != null && (count > 0 || mayDeferInstallation)) {
                logInfo("[CACHE] Candidate cache applied; registered " + count + " immediate hooks.")
                return
            }
            logInfo("[CACHE] No immediate installation; invalidating and rediscovering.")
        }
        // Each adapter owns a separate cache preference file. No user settings
        // are touched, and old schema/version-only entries are invalidated.
        synchronized(lifecycle) {
            if (detached || task != null) return
            prefs.edit().clear().apply()
            val executor = Executors.newSingleThreadExecutor()
            worker = executor
            task = executor.submit {
                try {
                    discover(context, prefs, key)
                } catch (t: Throwable) {
                    logError("[ERROR] Background discovery failed.", t)
                } finally {
                    executor.shutdown()
                }
            }
        }
    }

    private fun discover(context: Context, prefs: SharedPreferences, key: String?) {
        if (detached || Thread.currentThread().isInterrupted) return
        try {
            System.loadLibrary("dexkit")
        } catch (_: UnsatisfiedLinkError) {
            System.load(File(xposedModule.moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").absolutePath)
        }
        if (detached || Thread.currentThread().isInterrupted) return
        // Existing adapters write discovery candidates through SharedPreferences.
        // Isolate those writes from the persistent cache until this generation
        // has completed the scan and is still active. An old cancelled worker
        // cannot overwrite a newer generation's cache.
        val stagingName = prefsCacheName + "_scan_" + UUID.randomUUID().toString()
        val staging = context.getSharedPreferences(stagingName, Context.MODE_PRIVATE)
        try {
            val count = attempt {
                val bridge = if (context.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                    DexKitBridge.create(context.applicationInfo.sourceDir)
                } else {
                    DexKitBridge.create(targetClassLoader, true)
                }
                bridge.use {
                    if (!detached && !Thread.currentThread().isInterrupted) performSearchSynchronous(it, staging)
                }
            }
            synchronized(lifecycle) {
                if (detached || Thread.currentThread().isInterrupted) return
                val usable = count != null && hasCandidates(staging) && (count > 0 || mayDeferInstallation)
                if (usable && key != null) publishCache(prefs, staging, key)
                else prefs.edit().clear().apply()
                if (!usable || count == null || count == 0) {
                    logInfo("[DISCOVERY] No confirmed immediate installation; no success notification.")
                    return
                }
                logInfo("[DISCOVERY] Registered " + count + " immediate hooks; behavior still requires runtime validation.")
            }
        } finally {
            runCatching { context.deleteSharedPreferences(stagingName) }
        }
        Handler(Looper.getMainLooper()).post {
            if (!detached) {
                try {
                    Toast.makeText(context, successToastMessage, Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    logError("[WARN] Unable to display discovery notification.", t)
                }
            }
        }
    }

    private fun publishCache(destination: SharedPreferences, staging: SharedPreferences, key: String) {
        val editor = destination.edit().clear()
        for ((name, value) in staging.all) {
            if (name == DynamicCacheRules.BUILD_KEY || name == "app_version") continue
            when (value) {
                is String -> editor.putString(name, value)
                is Set<*> -> {
                    require(value.all { it is String })
                    editor.putStringSet(name, value.filterIsInstance<String>().toSet())
                }
                is Int -> editor.putInt(name, value)
                is Long -> editor.putLong(name, value)
                is Boolean -> editor.putBoolean(name, value)
                is Float -> editor.putFloat(name, value)
                else -> throw IllegalArgumentException("Unsupported cache entry")
            }
        }
        editor.putString(DynamicCacheRules.BUILD_KEY, key).apply()
    }

    protected abstract fun hasValidCache(cachePrefs: SharedPreferences): Boolean
    protected abstract fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences)
    protected abstract fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences)
}
