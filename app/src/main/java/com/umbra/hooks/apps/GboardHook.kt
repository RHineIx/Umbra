package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.net.Uri
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Modifier

class GboardHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_ADAPTER = "method_clipboard_adapter"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 24L * 60 * 60 * 1000
    @Volatile private var logsEnabled = false

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
    }

    private fun log(message: String) {
        if (logsEnabled) XposedBridge.log("$TAG $message")
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args[0] as Context
                        refreshConfig(context)
                        
                        log("Hooks Initializing for: ${lpparam.packageName}")
                        hookClipboardProvider(lpparam.classLoader)
                        setupDynamicHooks(context, lpparam.classLoader)
                        setupPrivacyHooks(context, lpparam.classLoader)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG attach error: $t")
                    }
                }
            }
        )
    }
    
    private fun refreshConfig(context: Context) {
        cachedLimit = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        val days = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION)
        cachedRetentionMs = days * 24L * 60 * 60 * 1000
        logsEnabled = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_LOGS, 0) == 1
    }

    private fun hookClipboardProvider(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sortOrder = param.args[4]?.toString() ?: ""
                        if (sortOrder.contains("limit 5")) {
                            param.args[4] = sortOrder.replace("limit 5", "limit $cachedLimit")
                            log("SQL Limit patched: $cachedLimit")
                        }
                    }
                }
            )
        } catch (t: Throwable) { log("Provider hook error: $t") }
    }

    private fun setupDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        val isSameVersion = (versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        var configMethod = if (isSameVersion) cachePrefs.getString(KEY_METHOD_CONFIG, null) else null
        var adapterMethod = if (isSameVersion) cachePrefs.getString(KEY_METHOD_ADAPTER, null) else null

        if (configMethod == null || adapterMethod == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    if (configMethod == null) {
                        configMethod = bridge.findMethod { matcher { usingStrings("Invalid flag:"); returnType("java.lang.Object") } }.firstOrNull()?.toDexMethod()?.serialize()
                    }
                    if (adapterMethod == null) {
                        adapterMethod = bridge.findClass { matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") } }
                                              .findMethod { matcher { usingNumbers(5) } }.firstOrNull()?.toDexMethod()?.serialize()
                    }
                    cachePrefs.edit().putInt(KEY_VERSION, versionCode).putString(KEY_METHOD_CONFIG, configMethod).putString(KEY_METHOD_ADAPTER, adapterMethod).apply()
                }
            } catch (_: Throwable) {}
        }

        configMethod?.let {
            try {
                val dm = DexMethod(it)
                XposedHelpers.findAndHookMethod(dm.className, classLoader, dm.name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = try { XposedHelpers.getObjectField(param.thisObject, "a").toString() } catch (_: Exception) { "" }
                        if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") {
                            param.result = false
                            log("Flag $name forced to false")
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        adapterMethod?.let {
            try {
                val dm = DexMethod(it)
                XposedHelpers.findAndHookMethod(dm.className, classLoader, dm.name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        log("Adapter UI limit bypassed")
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun setupPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        var metricsClass = if (versionCode == cachePrefs.getInt(KEY_VERSION, -1)) cachePrefs.getString(KEY_CLASS_METRICS, null) else null

        if (metricsClass == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    metricsClass = bridge.findClass { matcher { usingStrings("UserMetrics", "Failed to process metrics") } }.firstOrNull()?.name
                    cachePrefs.edit().putString(KEY_CLASS_METRICS, metricsClass).apply()
                }
            } catch (_: Throwable) {}
        }

        metricsClass?.let {
            try {
                val clazz = XposedHelpers.findClass(it, classLoader)
                clazz.declaredMethods.forEach { method ->
                    if (Modifier.isPublic(method.modifiers)) {
                        XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                log("Blocked telemetry: ${method.name}")
                                return null
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}