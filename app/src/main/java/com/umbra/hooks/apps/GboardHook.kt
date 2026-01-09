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
        
        // Cache keys for privacy hooks
        private const val KEY_CLASS_METRICS = "class_metrics_processor"
        
        private val LIMIT_REGEX = Regex("limit\\s+\\d+", RegexOption.IGNORE_CASE)
    }

    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    // Local runtime cache 
    @Volatile private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT
    @Volatile private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 24L * 60 * 60 * 1000

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
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
                        
                        // Fetch Config Remote
                        refreshConfig(context)
                        
                        val classLoader = lpparam.classLoader
                        
                        // 1. Core Clipboard Hooks
                        hookClipboardProvider(classLoader)
                        
                        // 2. Dynamic Flag Hooks (DexKit)
                        setupDynamicHooks(context, classLoader)
                        
                        // 3. Privacy Shield (DexKit - New Feature)
                        setupPrivacyHooks(context, classLoader)

                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG attach error: $t")
                    }
                }
            }
        )
    }
    
    private fun refreshConfig(context: Context) {
        val limit = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
        val days = PrefsManager.getRemoteInt(context, Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION)
        cachedLimit = limit
        cachedRetentionMs = days * 24L * 60 * 60 * 1000
    }

    // --- Clipboard Logic (Unchanged from original stable version) ---
    private fun hookClipboardProvider(classLoader: ClassLoader) {
        val providerClass = "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        try {
            XposedHelpers.findAndHookMethod(
                providerClass,
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val limit = cachedLimit
                        val retentionMs = cachedRetentionMs
                        
                        val cutoffTime = System.currentTimeMillis() - retentionMs
                        val selection = param.args[2]?.toString() ?: ""
                        val selectionArgs = param.args[3] as? Array<String>
                        val sortOrder = param.args[4]?.toString() ?: ""

                        if (selection.contains("timestamp >= ?") && selectionArgs != null) {
                            var index = 0
                            val targetPhrase = "timestamp >= ?"
                            val phraseIndex = selection.indexOf(targetPhrase)
                            if (phraseIndex != -1) {
                                for (i in 0 until phraseIndex) {
                                    if (selection[i] == '?') index++
                                }
                                if (index < selectionArgs.size) {
                                    selectionArgs[index] = cutoffTime.toString()
                                    param.args[3] = selectionArgs
                                }
                            }
                        }

                        val newOrder = when {
                            sortOrder.contains("limit", ignoreCase = true) -> {
                                sortOrder.replace(LIMIT_REGEX, "LIMIT $limit")
                            }
                            sortOrder.isNotBlank() -> {
                                "$sortOrder LIMIT $limit"
                            }
                            else -> {
                                "LIMIT $limit"
                            }
                        }
                        param.args[4] = newOrder
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Clipboard hook failed: $t")
        }
    }

    // --- DexKit Dynamic Hooks (Flags & Privacy) ---
    private fun setupDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) { -1 }

        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode == cachedVersion)

        // --- Part A: Config Flag Hook ---
        var methodName = if (isSameVersion) cachePrefs.getString(KEY_METHOD_CONFIG, null) else null

        if (methodName == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    val method = bridge.findMethod {
                        matcher {
                            usingStrings("Invalid flag:")
                            returnType("java.lang.Object")
                            paramCount(1)
                        }
                    }.firstOrNull()

                    if (method != null) {
                        methodName = method.toDexMethod().serialize()
                        cachePrefs.edit()
                            .putInt(KEY_VERSION, versionCode)
                            .putString(KEY_METHOD_CONFIG, methodName)
                            .apply()
                    }
                }
            } catch (_: Throwable) {}
        }

        if (methodName != null) {
            try {
                val dexMethod = DexMethod(methodName!!)
                XposedHelpers.findAndHookMethod(
                    dexMethod.className,
                    classLoader,
                    dexMethod.name,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val name = try {
                                XposedHelpers.getObjectField(param.thisObject, "a").toString()
                            } catch (e: Exception) { "" }
                            // Force enable clipboard features
                            if (name == "enable_clipboard_entity_extraction" || 
                                name == "enable_clipboard_query_refactoring") {
                                param.result = false
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                cachePrefs.edit().remove(KEY_METHOD_CONFIG).apply()
            }
        }
    }

    private fun setupPrivacyHooks(context: Context, classLoader: ClassLoader) {
        // Blocks analytics and telemetry
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try {
             context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) { -1 }
        
        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode == cachedVersion)

        var metricsClass = if (isSameVersion) cachePrefs.getString(KEY_CLASS_METRICS, null) else null

        if (metricsClass == null) {
             try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    // Search for the class that handles Metrics
                    // FIX: Replaced addString with usingStrings
                    
                    val foundData = bridge.findClass {
                        matcher {
                             usingStrings("UserMetrics", "Failed to process metrics")
                        }
                    }.firstOrNull()

                    if (foundData != null) {
                        metricsClass = foundData.name
                        cachePrefs.edit().putString(KEY_CLASS_METRICS, metricsClass).apply()
                        XposedBridge.log("$TAG Metrics class found via DexKit: $metricsClass")
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG DexKit Privacy search failed: $e")
            }
        }

        if (metricsClass != null) {
            try {
                // Hook ALL methods in this class to do nothing (void) or return null
                val clazz = XposedHelpers.findClass(metricsClass, classLoader)
                clazz.declaredMethods.forEach { method ->
                    if (Modifier.isPublic(method.modifiers)) {
                         XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                // Block execution
                                return null
                            }
                        })
                    }
                }
                XposedBridge.log("$TAG Privacy Shield Active: Blocked $metricsClass")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG Failed to hook Metrics class: $e")
                cachePrefs.edit().remove(KEY_CLASS_METRICS).apply()
            }
        }
    }
}
