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
        private const val KEY_METHOD_CLEANUP = "method_cleanup_list"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var logsEnabled = false

    // Matches: "limit 5", "LIMIT 5", "limit 10"
    private val limitPattern = "(?i)limit\\s+\\d+".toRegex()

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
                        
                        if (logsEnabled) log("Hooks Initializing. Limit: $cachedLimit")
                        
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
        logsEnabled = PrefsManager.getRemoteInt(context, Constants.KEY_GLOBAL_LOGS, 0) == 1
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
                        if (limitPattern.containsMatchIn(sortOrder)) {
                            val newSortOrder = sortOrder.replace(limitPattern, "LIMIT $cachedLimit")
                            param.args[4] = newSortOrder
                            log("SQL Limit patched: $newSortOrder")
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
        var cleanupMethod = if (isSameVersion) cachePrefs.getString(KEY_METHOD_CLEANUP, null) else null

        if (configMethod == null || cleanupMethod == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    // 1. Find the Class "ClipboardAdapter"
                    val adapterClassData = bridge.findClass { 
                        matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") } 
                    }.firstOrNull()

                    if (adapterClassData != null) {
                        // 2. Find Cleanup Method (void E() in your smali)
                        if (cleanupMethod == null) {
                             // FIX: Instead of guessing the DSL property for className,
                             // we search broadly and filter in Kotlin. This guarantees compilation.
                             cleanupMethod = bridge.findMethod {
                                matcher {
                                    returnType("void")
                                    usingNumbers(5)
                                }
                            }
                            .filter { it.className == adapterClassData.name } // Robust Filtering Here
                            .firstOrNull()?.toDexMethod()?.serialize()
                        }
                    }

                    // Config Method
                    if (configMethod == null) {
                        configMethod = bridge.findMethod { 
                            matcher { 
                                usingStrings("Invalid flag:")
                                returnType("java.lang.Object") 
                            } 
                        }.firstOrNull()?.toDexMethod()?.serialize()
                    }
                    
                    cachePrefs.edit()
                        .putInt(KEY_VERSION, versionCode)
                        .putString(KEY_METHOD_CONFIG, configMethod)
                        .putString(KEY_METHOD_CLEANUP, cleanupMethod)
                        .apply()
                }
            } catch (t: Throwable) { log("DexKit init error: $t") }
        }

        // Apply Flag Hook
        configMethod?.let {
            try {
                val dm = DexMethod(it)
                XposedHelpers.findAndHookMethod(dm.className, classLoader, dm.name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = try {
                            val fields = param.thisObject.javaClass.declaredFields
                            val stringField = fields.firstOrNull { f -> f.type == String::class.java }
                            stringField?.isAccessible = true
                            stringField?.get(param.thisObject)?.toString() ?: ""
                        } catch (_: Exception) { "" }

                        if (name == "enable_clipboard_entity_extraction" || name == "enable_clipboard_query_refactoring") {
                            param.result = false
                            log("Flag $name forced to false")
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        // Apply Cleanup Hook (The Limit 5 Fix)
        cleanupMethod?.let {
            try {
                val dm = DexMethod(it)
                // We REPLACE the method to do NOTHING. 
                // This prevents Gboard from deleting items > 5 from the UI list.
                XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(dm.className, classLoader, dm.name), 
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            log("Blocked UI cleanup (Limit 5 enforcement skipped).")
                            return null // Do nothing -> No items removed
                        }
                    }
                )
            } catch (t: Throwable) { log("Cleanup Hook Failed: $t") }
        }
    }

    private fun setupPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        var metricsClass = if (versionCode == cachePrefs.getInt(KEY_VERSION, -1)) cachePrefs.getString(KEY_CLASS_METRICS, null) else null

        if (metricsClass == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    metricsClass = bridge.findClass { 
                        matcher { usingStrings("UserMetrics", "Failed to process metrics") } 
                    }.firstOrNull()?.name
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
                                return null
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}