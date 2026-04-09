package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
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
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_CLEANUP = "method_cleanup_list"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
        private const val KEY_METHOD_RETENTION = "method_retention_logic"

        private const val GBOARD_DEFAULT_RETENTION_MS = 3600000L
        private const val CLEANUP_COOLDOWN_MS = 30000L
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

            prefs.registerOnSharedPreferenceChangeListener { p, _ ->
                refreshConfig(p)
            }
        } catch (t: Throwable) {
            logError("Failed to setup reactive prefs", t)
        }
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
            val queryMethod = providerClass.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java
            )

            xposedModule.hook(queryMethod).intercept { chain ->
                val originalArgs = chain.args
                val uri = originalArgs[0] as? Uri
                val sortOrder = originalArgs[4]?.toString() ?: ""

                if (uri != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastCleanupTime > CLEANUP_COOLDOWN_MS) {
                        lastCleanupTime = now
                        val threshold = now - cachedRetentionMs

                        backgroundExecutor.submit {
                            try {
                                context.contentResolver.delete(
                                    uri,
                                    "timestamp < ?",
                                    arrayOf(threshold.toString())
                                )
                            } catch (e: Throwable) {
                                logError("Proactive Cleanup failed", e)
                            }
                        }
                    }
                }

                if (limitPattern.containsMatchIn(sortOrder)) {
                    val newSortOrder = sortOrder.replace(limitPattern, "LIMIT $cachedLimit")
                    val newArgs = originalArgs.toTypedArray()
                    newArgs[4] = newSortOrder
                    return@intercept chain.proceed(newArgs)
                }

                return@intercept chain.proceed()
            }
        } catch (t: Throwable) { logError("Provider hook error", t) }
    }

    private fun handleDynamicAndPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

        @Suppress("DEPRECATION")
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }

        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode != -1 && versionCode == cachedVersion)

        if (isSameVersion) {
            applyDynamicHooks(
                classLoader,
                cachePrefs.getString(KEY_METHOD_CONFIG, null),
                cachePrefs.getString(KEY_METHOD_CLEANUP, null),
                cachePrefs.getString(KEY_METHOD_RETENTION, null)
            )
            applyPrivacyHooks(classLoader, cachePrefs.getString(KEY_CLASS_METRICS, null))
        } else {
            backgroundExecutor.submit {
                performBackgroundSearchAndHook(context, classLoader, versionCode, cachePrefs)
            }
        }
    }

    private fun performBackgroundSearchAndHook(
        context: Context,
        classLoader: ClassLoader,
        versionCode: Int,
        cachePrefs: SharedPreferences
    ) {
        var configMethod: String? = null
        var cleanupMethod: String? = null
        var metricsClass: String? = null
        var retentionMethod: String? = null

        try {
            val apkPath = context.applicationInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                val adapterClassData = bridge.findClass {
                    matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") }
                }.firstOrNull()

                if (adapterClassData != null) {
                    cleanupMethod = bridge.findMethod {
                        matcher {
                            returnType("void")
                            usingNumbers(5)
                        }
                    }.firstOrNull { it.className == adapterClassData.name }?.toDexMethod()?.serialize()
                }

                retentionMethod = bridge.findMethod {
                    matcher { usingNumbers(GBOARD_DEFAULT_RETENTION_MS) }
                }.firstOrNull()?.toDexMethod()?.serialize()

                configMethod = bridge.findMethod {
                    matcher {
                        usingStrings("Invalid flag:")
                        returnType("java.lang.Object")
                    }
                }.firstOrNull()?.toDexMethod()?.serialize()

                metricsClass = bridge.findClass {
                    matcher { usingStrings("UserMetrics", "Failed to process metrics") }
                }.firstOrNull()?.name
            }

            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putString(KEY_METHOD_CONFIG, configMethod)
                .putString(KEY_METHOD_CLEANUP, cleanupMethod)
                .putString(KEY_CLASS_METRICS, metricsClass)
                .putString(KEY_METHOD_RETENTION, retentionMethod)
                .apply()

            applyDynamicHooks(classLoader, configMethod, cleanupMethod, retentionMethod)
            applyPrivacyHooks(classLoader, metricsClass)
        } catch (t: Throwable) {
            logError("DexKit background init error", t)
        }
    }

    private fun applyDynamicHooks(
        classLoader: ClassLoader,
        configMethodStr: String?,
        cleanupMethodStr: String?,
        retentionMethodStr: String?
    ) {
        configMethodStr?.let {
            try {
                val dm = DexMethod(it)
                val clazz = classLoader.loadClass(dm.className)
                val method = clazz.declaredMethods.first { m -> m.name == dm.name }

                xposedModule.hook(method).intercept { chain ->
                    val name = try {
                        val fields = chain.thisObject?.javaClass?.declaredFields ?: emptyArray()
                        val stringField = fields.firstOrNull { f -> f.type == String::class.java }
                        stringField?.isAccessible = true
                        stringField?.get(chain.thisObject)?.toString() ?: ""
                    } catch (_: Exception) { "" }

                    if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") {
                        return@intercept false
                    }
                    return@intercept chain.proceed()
                }
            } catch (_: Throwable) {}
        }

        cleanupMethodStr?.let {
            try {
                val dm = DexMethod(it)
                val clazz = classLoader.loadClass(dm.className)
                val method = clazz.declaredMethods.first { m -> m.name == dm.name }

                xposedModule.hook(method).intercept { return@intercept null }
            } catch (_: Throwable) { }
        }

        retentionMethodStr?.let {
            try {
                val dm = DexMethod(it)
                val clazz = classLoader.loadClass(dm.className)
                val method = clazz.declaredMethods.first { m -> m.name == dm.name }

                xposedModule.hook(method).intercept { return@intercept null }
            } catch (_: Throwable) { }
        }
    }

    private fun applyPrivacyHooks(classLoader: ClassLoader, metricsClassStr: String?) {
        metricsClassStr?.let {
            try {
                val clazz = classLoader.loadClass(it)
                clazz.declaredMethods.forEach { method ->
                    if (Modifier.isPublic(method.modifiers)) {
                        xposedModule.hook(method).intercept { return@intercept null }
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}