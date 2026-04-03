package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Modifier

@SuppressLint("NewApi")
class GboardHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_CLEANUP = "method_cleanup_list"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var logsEnabled = false

    private lateinit var xposedModule: XposedModule
    private val limitPattern = "(?i)limit\\s+\\d+".toRegex()

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
                    refreshConfig(context)

                    if (logsEnabled) logInfo("Hooks Initializing. Limit: $cachedLimit")

                    hookClipboardProvider(classLoader)
                    handleDynamicAndPrivacyHooks(context, classLoader)
                } catch (t: Throwable) {
                    logError("attach error", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("Failed to hook Application.attach", t)
        }
    }

    private fun refreshConfig(context: Context) {
        cachedLimit = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        logsEnabled = PrefsManager.getRemoteInt(context, Constants.KEY_GLOBAL_LOGS, 0) == 1
    }

    private fun hookClipboardProvider(classLoader: ClassLoader) {
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
                val sortOrder = originalArgs[4]?.toString() ?: ""

                if (limitPattern.containsMatchIn(sortOrder)) {
                    val newSortOrder = sortOrder.replace(limitPattern, "LIMIT $cachedLimit")
                    logInfo("SQL Limit patched: $newSortOrder")

                    // تحويل القائمة المعزولة إلى مصفوفة (Array) قابلة للتعديل بأمان
                    val newArgs = originalArgs.toTypedArray()

                    // التعديل حصراً على المصفوفة الجديدة
                    newArgs[4] = newSortOrder

                    // تمرير المصفوفة المعدلة إلى مسار التنفيذ
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
            logInfo("Cache hit (Version: $versionCode). Applying hooks synchronously.")
            val configMethod = cachePrefs.getString(KEY_METHOD_CONFIG, null)
            val cleanupMethod = cachePrefs.getString(KEY_METHOD_CLEANUP, null)
            val metricsClass = cachePrefs.getString(KEY_CLASS_METRICS, null)

            applyDynamicHooks(classLoader, configMethod, cleanupMethod)
            applyPrivacyHooks(classLoader, metricsClass)
        } else {
            logInfo("Cache miss. Starting background DexKit search...")
            Thread {
                performBackgroundSearchAndHook(context, classLoader, versionCode, cachePrefs)
            }.start()
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
                    }
                        .filter { it.className == adapterClassData.name }
                        .firstOrNull()?.toDexMethod()?.serialize()
                }

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
                .apply()

            logInfo("Background search completed. Results cached.")

            applyDynamicHooks(classLoader, configMethod, cleanupMethod)
            applyPrivacyHooks(classLoader, metricsClass)

        } catch (t: Throwable) {
            logError("DexKit background init error", t)
        }
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, configMethodStr: String?, cleanupMethodStr: String?) {
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
                        logInfo("Flag $name forced to false")
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

                xposedModule.hook(method).intercept {
                    logInfo("Blocked UI cleanup (Limit 5 enforcement skipped).")
                    return@intercept null
                }
            } catch (t: Throwable) { logError("Cleanup Hook Failed", t) }
        }
    }

    private fun applyPrivacyHooks(classLoader: ClassLoader, metricsClassStr: String?) {
        metricsClassStr?.let {
            try {
                val clazz = classLoader.loadClass(it)
                clazz.declaredMethods.forEach { method ->
                    if (Modifier.isPublic(method.modifiers)) {
                        xposedModule.hook(method).intercept {
                            return@intercept null
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}