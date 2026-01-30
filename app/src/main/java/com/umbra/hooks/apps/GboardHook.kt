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
        
        // Keys for caching
        private const val KEY_TARGET_VERSION = "cached_gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val KEY_METHOD_ADAPTER = "method_clipboard_adapter"
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var logsEnabled = false

    // Linked to Global Preference
    private fun log(message: String) {
        if (logsEnabled) {
            XposedBridge.log("$TAG $message")
        }
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

                        if (logsEnabled) log("Context attached. Limit: $cachedLimit")

                        // 1. Hook Clipboard Provider (Safe & Essential)
                        hookClipboardProvider(lpparam.classLoader)

                        // 2. Smart Hooking (DexKit Analysis with Cache)
                        initializeHooksSmartly(context, lpparam.classLoader)

                    } catch (t: Throwable) {
                        // Always log fatal errors regardless of setting
                        XposedBridge.log("$TAG Fatal Error in attach: ${t.message}")
                    }
                }
            }
        )
    }

    private fun refreshConfig(context: Context) {
        cachedLimit = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        // Now using GLOBAL LOGS Key
        logsEnabled = PrefsManager.getRemoteInt(context, Constants.KEY_GLOBAL_LOGS, 0) == 1
    }

    private fun initializeHooksSmartly(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { -1 }

        val cachedVersion = cachePrefs.getInt(KEY_TARGET_VERSION, -1)
        val useCache = (currentVersion == cachedVersion) && (currentVersion != -1)

        var configMethodStr: String? = null
        var adapterMethodStr: String? = null
        var metricsClassStr: String? = null

        if (useCache) {
            log("Using FAST PATH (Cache).")
            configMethodStr = cachePrefs.getString(KEY_METHOD_CONFIG, null)
            adapterMethodStr = cachePrefs.getString(KEY_METHOD_ADAPTER, null)
            metricsClassStr = cachePrefs.getString(KEY_CLASS_METRICS, null)
        } else {
            log("Using SLOW PATH (DexKit Analysis).")
        }

        // Integrity Check: If any cached item is missing, force DexKit fallback
        if (configMethodStr == null || adapterMethodStr == null || metricsClassStr == null) {
            try {
                // Critical: Load library explicitly to prevent UnsatisfiedLinkError
                System.loadLibrary("dexkit")
                
                DexKitBridge.create(classLoader, true).use { bridge ->
                    // A. Config Method
                    if (configMethodStr == null) {
                        configMethodStr = bridge.findMethod {
                            matcher {
                                usingStrings("Invalid flag:")
                                returnType("java.lang.Object")
                            }
                        }.firstOrNull()?.toDexMethod()?.serialize()
                    }

                    // B. Adapter Method
                    if (adapterMethodStr == null) {
                        adapterMethodStr = bridge.findClass {
                            matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") }
                        }.findMethod {
                            matcher { usingNumbers(5) }
                        }.firstOrNull()?.toDexMethod()?.serialize()
                    }

                    // C. Metrics Class
                    if (metricsClassStr == null) {
                        metricsClassStr = bridge.findClass {
                            matcher { usingStrings("UserMetrics", "Failed to process metrics") }
                        }.firstOrNull()?.name
                    }
                }

                // Update Cache
                cachePrefs.edit()
                    .putInt(KEY_TARGET_VERSION, currentVersion)
                    .putString(KEY_METHOD_CONFIG, configMethodStr)
                    .putString(KEY_METHOD_ADAPTER, adapterMethodStr)
                    .putString(KEY_CLASS_METRICS, metricsClassStr)
                    .apply()
                
                log("DexKit Analysis Complete. Cache Updated.")

            } catch (e: Throwable) {
                XposedBridge.log("$TAG DexKit Error: $e")
                return
            }
        }

        applyDynamicHooks(classLoader, configMethodStr, adapterMethodStr)
        applyPrivacyHooks(classLoader, metricsClassStr)
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, configStr: String?, adapterStr: String?) {
        // [DISABLED] Config Hook to prevent ClassCastException on recent Gboard versions
        /*
        configStr?.let { ... }
        */

        // Hook 2: Clipboard Adapter Limit (UI Limit)
        adapterStr?.let {
            try {
                val dm = DexMethod(it)
                XposedHelpers.findAndHookMethod(dm.className, classLoader, dm.name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Bypassing UI limit by returning null (voiding the check)
                        param.result = null 
                    }
                })
            } catch (t: Throwable) { log("Adapter Hook Failed: $t") }
        }
    }

    private fun applyPrivacyHooks(classLoader: ClassLoader, className: String?) {
        className?.let {
            try {
                val clazz = XposedHelpers.findClass(it, classLoader)
                clazz.declaredMethods.forEach { method ->
                    if (Modifier.isPublic(method.modifiers)) {
                        XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                return null // Suppress execution
                            }
                        })
                    }
                }
                log("Privacy hooks applied to $className")
            } catch (t: Throwable) { log("Privacy hook error: $t") }
        }
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
                        
                        // Robust Regex matching for "limit 5" (Case insensitive, spacing tolerant)
                        val limitPattern = "(?i)limit\\s+5".toRegex()
                        
                        if (limitPattern.containsMatchIn(sortOrder)) {
                            val newSortOrder = sortOrder.replace(limitPattern, "limit $cachedLimit")
                            param.args[4] = newSortOrder
                            log("SQL PATCHED: $newSortOrder")
                        }
                    }
                }
            )
        } catch (t: Throwable) { log("Provider hook error: $t") }
    }
}