package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
                        
                        // 1. Fast, synchronous hook for known classes (No DexKit needed)
                        hookClipboardProvider(lpparam.classLoader)
                        
                        // 2. Heavy DexKit operations handled via Cache or Background Thread
                        handleDynamicAndPrivacyHooks(context, lpparam.classLoader)
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

    private fun handleDynamicAndPrivacyHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        
        @Suppress("DEPRECATION")
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        
        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode != -1 && versionCode == cachedVersion)

        if (isSameVersion) {
            // Cache Hit: Rely ONLY on versionCode match. Apply whatever we found previously.
            log("Cache hit (Version: $versionCode). Applying hooks synchronously.")
            
            val configMethod = cachePrefs.getString(KEY_METHOD_CONFIG, null)
            val cleanupMethod = cachePrefs.getString(KEY_METHOD_CLEANUP, null)
            val metricsClass = cachePrefs.getString(KEY_CLASS_METRICS, null)
            
            applyDynamicHooks(classLoader, configMethod, cleanupMethod)
            applyPrivacyHooks(classLoader, metricsClass)
        } else {
            // Cache Miss: Offload heavy DexKit search to background thread to prevent ANR
            log("Cache miss (Current: $versionCode, Cached: $cachedVersion). Starting background DexKit search...")
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
            DexKitBridge.create(classLoader, true).use { bridge ->
                // --- 1. Dynamic Hooks (Clipboard Adapter) ---
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
                    .filter { it.className == adapterClassData.name } // Robust Filtering Here
                    .firstOrNull()?.toDexMethod()?.serialize()
                }

                // Config Method
                configMethod = bridge.findMethod { 
                    matcher { 
                        usingStrings("Invalid flag:")
                        returnType("java.lang.Object") 
                    } 
                }.firstOrNull()?.toDexMethod()?.serialize()

                // --- 2. Privacy Hooks (UserMetrics) ---
                metricsClass = bridge.findClass { 
                    matcher { usingStrings("UserMetrics", "Failed to process metrics") } 
                }.firstOrNull()?.name
            }

            // --- 3. Save to Cache (Always save to prevent infinite background loops on unsupported versions) ---
            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putString(KEY_METHOD_CONFIG, configMethod)
                .putString(KEY_METHOD_CLEANUP, cleanupMethod)
                .putString(KEY_CLASS_METRICS, metricsClass)
                .apply()
                
            log("Background search completed. Results cached for version $versionCode.")

            // --- 4. Apply Hooks ---
            applyDynamicHooks(classLoader, configMethod, cleanupMethod)
            applyPrivacyHooks(classLoader, metricsClass)

        } catch (t: Throwable) { 
            log("DexKit background init error: $t") 
        }
    }

    private fun applyDynamicHooks(classLoader: ClassLoader, configMethodStr: String?, cleanupMethodStr: String?) {
        // Apply Flag Hook
        configMethodStr?.let {
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
        cleanupMethodStr?.let {
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

    private fun applyPrivacyHooks(classLoader: ClassLoader, metricsClassStr: String?) {
        metricsClassStr?.let {
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