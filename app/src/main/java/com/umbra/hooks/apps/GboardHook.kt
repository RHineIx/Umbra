package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Modifier
import java.util.concurrent.*

@SuppressLint("NewApi")
class GboardHook : AppHook {

    companion object {
        private const val TAG = "UMBRA-GBOARD"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_CLASS_ADAPTER = "class_clipboard_adapter"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 86400000L
    @Volatile private var logsEnabled = false
    @Volatile private var lastCleanupTime = 0L

    private lateinit var xposedModule: XposedModule
    private val limitPattern = "(?i)limit\\s+\\d+".toRegex()
    
    private val backgroundExecutor: ExecutorService = ThreadPoolExecutor(
        0, 1,
        60L, TimeUnit.SECONDS,
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
                    logInfo("[INFO] Application.attach intercepted. Initializing hooks...")
                    setupPrefs()
                    hookClipboardProvider(classLoader, context)
                    handleDynamicAndPrivacyHooks(context, classLoader)
                } catch (t: Throwable) {
                    logError("[ERROR] Failure during Application.attach processing", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("[ERROR] Failed to locate or hook Application.attach", t)
        }
    }

    private fun setupPrefs() {
        try {
            val prefs = xposedModule.getRemotePreferences(Constants.PREFS_FILE)
            refreshConfig(prefs)
        } catch (t: Throwable) { 
            logError("[ERROR] Failed to fetch remote preferences", t) 
        }
    }

    private fun refreshConfig(prefs: SharedPreferences) {
        cachedLimit = prefs.getInt(Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        val days = prefs.getInt(Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION)
        cachedRetentionMs = days * 86400000L
        logsEnabled = prefs.getInt(Constants.KEY_GLOBAL_LOGS, 0) == 1
        logInfo("[CONFIG] Limit: $cachedLimit | Retention: $days days | Logs: $logsEnabled")
    }

    private fun hookClipboardProvider(classLoader: ClassLoader, context: Context) {
        try {
            val providerClass = classLoader.loadClass("com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider")
            val queryMethod = providerClass.getDeclaredMethod("query", Uri::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java)

            xposedModule.hook(queryMethod).intercept { chain ->
                val uri = chain.args[0] as? Uri
                val sortOrder = chain.args[4]?.toString() ?: ""

                if (uri != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastCleanupTime > 30000L) {
                        lastCleanupTime = now
                        backgroundExecutor.submit {
                            try { 
                                val deleted = context.contentResolver.delete(uri, "timestamp < ?", arrayOf((now - cachedRetentionMs).toString())) 
                                if (deleted > 0) logInfo("[CLEANUP] Removed $deleted expired clipboard items.")
                            } catch (e: Throwable) { 
                                logError("[ERROR] Background cleanup task failed", e) 
                            }
                        }
                    }
                }

                if (limitPattern.containsMatchIn(sortOrder)) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[4] = sortOrder.replace(limitPattern, "LIMIT $cachedLimit")
                    return@intercept chain.proceed(newArgs)
                }
                return@intercept chain.proceed()
            }
            logInfo("[SUCCESS] Clipboard provider query method hooked.")
        } catch (t: Throwable) { 
            logError("[ERROR] Failed to hook clipboard provider", t) 
        }
    }

    private fun handleDynamicAndPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        if (isSameVersion) {
            logInfo("[CACHE] Version matches ($versionCode). Applying cached offsets.")
            applyDynamicHooks(classLoader, cachePrefs.getString(KEY_METHOD_CONFIG, null), cachePrefs.getString(KEY_CLASS_ADAPTER, null))
            applyPrivacyHooks(classLoader, cachePrefs.getString(KEY_CLASS_METRICS, null))
        } else {
            logInfo("[UPDATE] New version ($versionCode) detected. Initiating DexKit search...")
            backgroundExecutor.submit {
                try {
                    performBackgroundSearchAndHook(context, classLoader, versionCode, cachePrefs)
                    logInfo("[SUCCESS] DexKit background search completed.")
                    
                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(context, "Umbra: Gboard offsets updated. Please restart.", Toast.LENGTH_LONG).show()
                        } catch (e: Throwable) {
                            logError("[WARN] Context invalid for Toast display", e)
                        }
                    }
                } catch (t: Throwable) {
                    logError("[ERROR] Background search and hook failed", t)
                }
            }
        }
    }

    private fun loadDexKitSafely() {
        try {
            System.loadLibrary("dexkit")
        } catch (e: UnsatisfiedLinkError) {
            logInfo("[WARN] Standard loadLibrary failed. Attempting absolute path fallback...")
            try {
                val appInfoMethod = xposedModule.javaClass.methods.firstOrNull { 
                    it.name == "getApplicationInfo" || it.name == "getModuleApplicationInfo" 
                }
                val appInfo = appInfoMethod?.invoke(xposedModule) as? android.content.pm.ApplicationInfo
                
                if (appInfo != null) {
                    val libPath = "${appInfo.nativeLibraryDir}/libdexkit.so"
                    System.load(libPath)
                    logInfo("[SUCCESS] libdexkit.so loaded from absolute path.")
                } else {
                    throw UnsatisfiedLinkError("Could not resolve module ApplicationInfo")
                }
            } catch (e2: Throwable) {
                logError("[FATAL] Failed to load libdexkit.so.", e2)
                throw e2
            }
        }
    }

    private fun performBackgroundSearchAndHook(context: Context, classLoader: ClassLoader, versionCode: Int, cachePrefs: SharedPreferences) {
        try {
            loadDexKitSafely()
        } catch (e: Throwable) {
            return
        }

        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                // Search for ClipboardAdapter class using the verified string constant
                val adapterClass = bridge.findClass { matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") } }.firstOrNull()?.name
                val config = bridge.findMethod { matcher { usingStrings("Invalid flag:"); returnType("java.lang.Object") } }.firstOrNull()?.toDexMethod()?.serialize()
                val metrics = bridge.findClass { matcher { usingStrings("UserMetrics", "Failed to process metrics") } }.firstOrNull()?.name

                logInfo("[SEARCH] Results -> Adapter: ${adapterClass != null}, Config: ${config != null}, Metrics: ${metrics != null}")

                cachePrefs.edit()
                    .putInt(KEY_VERSION, versionCode)
                    .putString(KEY_METHOD_CONFIG, config)
                    .putString(KEY_CLASS_ADAPTER, adapterClass)
                    .putString(KEY_CLASS_METRICS, metrics)
                    .apply()
                
                applyDynamicHooks(classLoader, config, adapterClass)
                applyPrivacyHooks(classLoader, metrics)
            }
        } catch (t: Throwable) { 
            logError("[ERROR] Exception occurred during DexKit search", t) 
        }
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, config: String?, adapterClassName: String?) {
        if (!config.isNullOrBlank()) {
            try {
                val dm = DexMethod(config)
                val method = classLoader.loadClass(dm.className).declaredMethods.firstOrNull { m -> m.name == dm.name }
                if (method != null) {
                    xposedModule.hook(method).intercept { chain ->
                        val name = try {
                            val f = chain.thisObject?.javaClass?.declaredFields?.firstOrNull { it.type == String::class.java }
                            f?.isAccessible = true
                            f?.get(chain.thisObject)?.toString() ?: ""
                        } catch (_: Exception) { "" }
                        
                        if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") {
                            false 
                        } else {
                            chain.proceed()
                        }
                    }
                }
            } catch (e: Throwable) { 
                logError("[WARN] Dynamic config target not found.", e) 
            }
        }

        // Hook safety checks or internal methods inside ClipboardAdapter if needed
        if (!adapterClassName.isNullOrBlank()) {
            try {
                val adapterCls = classLoader.loadClass(adapterClassName)
                logInfo("[SUCCESS] Successfully resolved ClipboardAdapter class: $adapterClassName")
            } catch (e: Throwable) {
                logError("[WARN] Failed to load resolved ClipboardAdapter class", e)
            }
        }
    }

    private fun applyPrivacyHooks(classLoader: ClassLoader, metricsClass: String?) {
        if (!metricsClass.isNullOrBlank()) {
            try {
                var hookedCount = 0
                val loadedClass = classLoader.loadClass(metricsClass)
                loadedClass.declaredMethods.forEach { m ->
                    if (Modifier.isPublic(m.modifiers)) {
                        xposedModule.hook(m).intercept { null }
                        hookedCount++
                    }
                }
                logInfo("[SUCCESS] UserMetrics silenced. Hooked $hookedCount methods.")
            } catch (e: Throwable) { 
                logError("[WARN] Failed to apply UserMetrics privacy hooks.", e) 
            }
        }
    }
}