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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("NewApi")
class GboardHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_CLEANUP = "method_cleanup_list"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
        private const val KEY_METHOD_RETENTION = "method_retention_logic"
        private const val DEXKIT_TIMEOUT_MS = 2500L
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 86400000L
    @Volatile private var logsEnabled = false
    @Volatile private var lastCleanupTime = 0L

    private lateinit var xposedModule: XposedModule
    private val limitPattern = "(?i)limit\\s+\\d+".toRegex()
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
    }

    private fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (t != null) xposedModule.log(Log.ERROR, TAG, message, t)
        else xposedModule.log(Log.ERROR, TAG, message)
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
                    setupReactivePrefs()
                    hookClipboardProvider(classLoader, context)
                    handleDynamicAndPrivacyHooks(context, classLoader)
                } catch (t: Throwable) {
                    logError("Application.attach hook error", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("Failed to hook Application.attach", t)
        }
    }

    private fun setupReactivePrefs() {
        try {
            val prefs = xposedModule.getRemotePreferences(Constants.PREFS_FILE)
            refreshConfig(prefs)
            prefs.registerOnSharedPreferenceChangeListener { p, _ -> refreshConfig(p) }
        } catch (t: Throwable) { logError("Reactive prefs failed", t) }
    }

    private fun refreshConfig(prefs: SharedPreferences) {
        cachedLimit = prefs.getInt(Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        val days = prefs.getInt(Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION)
        cachedRetentionMs = days * 86400000L
        logsEnabled = prefs.getInt(Constants.KEY_GLOBAL_LOGS, 0) == 1
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
                            try { context.contentResolver.delete(uri, "timestamp < ?", arrayOf((now - cachedRetentionMs).toString())) } 
                            catch (e: Throwable) { logError("Cleanup failed", e) }
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
        } catch (t: Throwable) { logError("Provider hook error", t) }
    }

    private fun handleDynamicAndPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        if (isSameVersion) {
            applyDynamicHooks(classLoader, cachePrefs.getString(KEY_METHOD_CONFIG, null), cachePrefs.getString(KEY_METHOD_CLEANUP, null), cachePrefs.getString(KEY_METHOD_RETENTION, null))
            applyPrivacyHooks(classLoader, cachePrefs.getString(KEY_CLASS_METRICS, null))
        } else {
            // Solve Race Condition: Block main thread for a limited time during first update
            val latch = CountDownLatch(1)
            backgroundExecutor.submit {
                performBackgroundSearchAndHook(context, classLoader, versionCode, cachePrefs)
                latch.countDown()
            }
            try {
                if (!latch.await(DEXKIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Umbra: Updating offsets, please restart app for full protection.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: InterruptedException) {}
        }
    }

    private fun performBackgroundSearchAndHook(context: Context, classLoader: ClassLoader, versionCode: Int, cachePrefs: SharedPreferences) {
        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                val adapterClass = bridge.findClass { matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") } }.firstOrNull()
                val cleanup = if (adapterClass != null) bridge.findMethod { matcher { returnType("void"); usingNumbers(5) } }.firstOrNull { it.className == adapterClass.name }?.toDexMethod()?.serialize() else null
                val retention = bridge.findMethod { matcher { usingNumbers(3600000L) } }.firstOrNull()?.toDexMethod()?.serialize()
                val config = bridge.findMethod { matcher { usingStrings("Invalid flag:"); returnType("java.lang.Object") } }.firstOrNull()?.toDexMethod()?.serialize()
                val metrics = bridge.findClass { matcher { usingStrings("UserMetrics", "Failed to process metrics") } }.firstOrNull()?.name

                cachePrefs.edit().putInt(KEY_VERSION, versionCode).putString(KEY_METHOD_CONFIG, config).putString(KEY_METHOD_CLEANUP, cleanup).putString(KEY_CLASS_METRICS, metrics).putString(KEY_METHOD_RETENTION, retention).apply()
                applyDynamicHooks(classLoader, config, cleanup, retention)
                applyPrivacyHooks(classLoader, metrics)
            }
        } catch (t: Throwable) { logError("DexKit update error", t) }
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, config: String?, cleanup: String?, retention: String?) {
        config?.let { try {
            val dm = DexMethod(it)
            val method = classLoader.loadClass(dm.className).declaredMethods.first { m -> m.name == dm.name }
            xposedModule.hook(method).intercept { chain ->
                val name = try {
                    val f = chain.thisObject?.javaClass?.declaredFields?.firstOrNull { it.type == String::class.java }
                    f?.isAccessible = true
                    f?.get(chain.thisObject)?.toString() ?: ""
                } catch (_: Exception) { "" }
                if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") false else chain.proceed()
            }
        } catch (_: Throwable) {} }

        cleanup?.let { try {
            val dm = DexMethod(it)
            xposedModule.hook(classLoader.loadClass(dm.className).getDeclaredMethod(dm.name)).intercept { null }
        } catch (_: Throwable) {} }

        retention?.let { try {
            val dm = DexMethod(it)
            xposedModule.hook(classLoader.loadClass(dm.className).getDeclaredMethod(dm.name)).intercept { null }
        } catch (_: Throwable) {} }
    }

    private fun applyPrivacyHooks(classLoader: ClassLoader, metricsClass: String?) {
        metricsClass?.let { try {
            classLoader.loadClass(it).declaredMethods.forEach { m ->
                if (Modifier.isPublic(m.modifiers)) xposedModule.hook(m).intercept { null }
            }
        } catch (_: Throwable) {} }
    }
}