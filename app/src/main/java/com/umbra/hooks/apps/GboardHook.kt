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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("NewApi")
class GboardHook : AppHook {

    companion object {
        private const val TAG = "UMBRA-GBOARD"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_CLEANUP = "method_cleanup_list"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
        private const val KEY_METHOD_RETENTION = "method_retention_logic"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 86400000L
    @Volatile private var logsEnabled = false
    @Volatile private var lastCleanupTime = 0L

    private lateinit var xposedModule: XposedModule
    private val limitPattern = "(?i)limit\\s+\\d+".toRegex()
    
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
                    setupReactivePrefs()
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

    private fun setupReactivePrefs() {
        try {
            val prefs = xposedModule.getRemotePreferences(Constants.PREFS_FILE)
            refreshConfig(prefs)
            prefs.registerOnSharedPreferenceChangeListener { p, _ -> 
                logInfo("[INFO] Remote preferences changed. Refreshing configuration.")
                refreshConfig(p) 
            }
            logInfo("[SUCCESS] Reactive preferences bound successfully.")
        } catch (t: Throwable) { 
            logError("[ERROR] Failed to bind reactive preferences", t) 
        }
    }

    private fun refreshConfig(prefs: SharedPreferences) {
        cachedLimit = prefs.getInt(Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        val days = prefs.getInt(Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION)
        cachedRetentionMs = days * 86400000L
        logsEnabled = prefs.getInt(Constants.KEY_GLOBAL_LOGS, 0) == 1
        logInfo("[CONFIG] Limit: $cachedLimit items | Retention: $days days | Logs: $logsEnabled")
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
                                logInfo("[CLEANUP] Background execution removed $deleted expired clipboard items.")
                            } catch (e: Throwable) { 
                                logError("[ERROR] Background cleanup task failed", e) 
                            }
                        }
                    }
                }

                if (limitPattern.containsMatchIn(sortOrder)) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[4] = sortOrder.replace(limitPattern, "LIMIT $cachedLimit")
                    logInfo("[HOOK] Intercepted clipboard query. Injected new limit: $cachedLimit.")
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
            logInfo("[CACHE] Version code matches ($versionCode). Applying cached hook offsets.")
            applyDynamicHooks(classLoader, cachePrefs.getString(KEY_METHOD_CONFIG, null), cachePrefs.getString(KEY_METHOD_CLEANUP, null), cachePrefs.getString(KEY_METHOD_RETENTION, null))
            applyPrivacyHooks(classLoader, cachePrefs.getString(KEY_CLASS_METRICS, null))
        } else {
            logInfo("[UPDATE] New version code detected ($versionCode). Initiating DexKit async background search...")
            backgroundExecutor.submit {
                try {
                    performBackgroundSearchAndHook(context, classLoader, versionCode, cachePrefs)
                    logInfo("[SUCCESS] DexKit background search completed.")
                    
                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(context, "Umbra: Gboard offsets updated. Please restart the app.", Toast.LENGTH_LONG).show()
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
            logInfo("[DEBUG] libdexkit.so loaded via standard System.loadLibrary.")
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
                    logInfo("[SUCCESS] libdexkit.so loaded successfully from absolute path: $libPath")
                } else {
                    throw UnsatisfiedLinkError("Could not resolve module ApplicationInfo")
                }
            } catch (e2: Throwable) {
                logError("[FATAL] Failed to load libdexkit.so natively. DexKit will not function.", e2)
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
            logInfo("[SEARCH] DexKitBridge instantiated. Analyzing base.apk...")
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                val adapterClass = bridge.findClass { matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") } }.firstOrNull()
                val cleanup = if (adapterClass != null) bridge.findMethod { matcher { returnType("void"); usingNumbers(5) } }.firstOrNull { it.className == adapterClass.name }?.toDexMethod()?.serialize() else null
                val retention = bridge.findMethod { matcher { usingNumbers(3600000L) } }.firstOrNull()?.toDexMethod()?.serialize()
                val config = bridge.findMethod { matcher { usingStrings("Invalid flag:"); returnType("java.lang.Object") } }.firstOrNull()?.toDexMethod()?.serialize()
                val metrics = bridge.findClass { matcher { usingStrings("UserMetrics", "Failed to process metrics") } }.firstOrNull()?.name

                logInfo("[SEARCH] Results -> Config: ${config != null}, Cleanup: ${cleanup != null}, Retention: ${retention != null}, Metrics: ${metrics != null}")

                cachePrefs.edit().putInt(KEY_VERSION, versionCode).putString(KEY_METHOD_CONFIG, config).putString(KEY_METHOD_CLEANUP, cleanup).putString(KEY_CLASS_METRICS, metrics).putString(KEY_METHOD_RETENTION, retention).apply()
                
                applyDynamicHooks(classLoader, config, cleanup, retention)
                applyPrivacyHooks(classLoader, metrics)
            }
        } catch (t: Throwable) { 
            logError("[ERROR] Exception occurred during DexKit search phase", t) 
        }
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, config: String?, cleanup: String?, retention: String?) {
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
                            logInfo("[HOOK] Blocked remote config flag: $name")
                            false 
                        } else {
                            chain.proceed()
                        }
                    }
                    logInfo("[SUCCESS] Dynamic configuration hook applied.")
                }
            } catch (e: Throwable) { 
                logError("[WARN] Dynamic config target not found or invalid. Skipping.", e) 
            }
        }

        if (!cleanup.isNullOrBlank()) {
            try {
                val dm = DexMethod(cleanup)
                val cleanupMethod = classLoader.loadClass(dm.className).getDeclaredMethod(dm.name)
                xposedModule.hook(cleanupMethod).intercept { null }
                logInfo("[SUCCESS] Local cleanup protocol neutralized.")
            } catch (e: Throwable) { 
                logInfo("[WARN] Local cleanup target not found. Likely removed in this version. Skipping.") 
            }
        }

        if (!retention.isNullOrBlank()) {
            try {
                val dm = DexMethod(retention)
                val retentionMethod = classLoader.loadClass(dm.className).getDeclaredMethod(dm.name)
                xposedModule.hook(retentionMethod).intercept { null }
                logInfo("[SUCCESS] Internal retention logic neutralized.")
            } catch (e: Throwable) { 
                logInfo("[WARN] Internal retention target not found. Likely removed in this version. Skipping.") 
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
                logInfo("[SUCCESS] UserMetrics silenced. Hooked $hookedCount public tracking methods.")
            } catch (e: Throwable) { 
                logError("[WARN] Failed to apply UserMetrics privacy hooks. Class might be missing.", e) 
            }
        }
    }
}