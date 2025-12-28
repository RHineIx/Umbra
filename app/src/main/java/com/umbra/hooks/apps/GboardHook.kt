package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.net.Uri
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod

class GboardHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
        private const val PREFS_TTL_MS = 3000L

        // OPTIMIZATION: Compile Regex once and reuse it globally.
        // Prevents CPU churn during every clipboard query.
        private val LIMIT_REGEX = Regex("limit\\s+\\d+", RegexOption.IGNORE_CASE)

        // Keep a single instance to avoid repetitive object allocation
        private var prefsInstance: XSharedPreferences? = null
    }

    override val targetPackages = setOf(
        "com.google.android.inputmethod.latin"
    )

    override fun isEnabled(prefs: XSharedPreferences): Boolean {
        return prefs.getBoolean(Constants.KEY_GBOARD_ENABLED, true)
    }

    @Volatile
    private var cachedLimit = Constants.DEFAULT_GBOARD_LIMIT

    @Volatile
    private var cachedRetentionMs = Constants.DEFAULT_GBOARD_RETENTION * 24L * 60 * 60 * 1000

    @Volatile
    private var lastPrefsRead = 0L

    init {
        try {
            System.loadLibrary("dexkit")
        } catch (_: Throwable) {
        }
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        init(lpparam)
    }

    private fun getPrefs(): XSharedPreferences? {
        // Recycle the existing instance, just reload content
        if (prefsInstance == null) {
            prefsInstance = XSharedPreferences("com.umbra.hooks", Constants.PREFS_FILE).apply {
                makeWorldReadable()
            }
        } else {
            prefsInstance?.reload()
        }
        
        val p = prefsInstance
        return if (p != null && p.file.exists() && p.file.canRead()) p else null
    }

    private fun getCachedConfig(): Pair<Int, Long> {
        val now = System.currentTimeMillis()
        if (now - lastPrefsRead > PREFS_TTL_MS) {
            try {
                val prefs = getPrefs()
                if (prefs != null) {
                    cachedLimit = prefs.getInt(
                        Constants.KEY_GBOARD_LIMIT,
                        Constants.DEFAULT_GBOARD_LIMIT
                    )

                    val days = prefs.getInt(
                        Constants.KEY_GBOARD_RETENTION_DAYS,
                        Constants.DEFAULT_GBOARD_RETENTION
                    )

                    cachedRetentionMs = days * 24L * 60 * 60 * 1000
                    lastPrefsRead = now
                }
            } catch (_: Throwable) {
            }
        }
        return cachedLimit to cachedRetentionMs
    }

    private fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args[0] as Context
                        val classLoader = lpparam.classLoader
                        
                        hookClipboardProvider(classLoader)
                        setupDynamicHooks(context, classLoader)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG attach error: $t")
                    }
                }
            }
        )
    }

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
                        val (limit, retentionMs) = getCachedConfig()
                        val cutoffTime = System.currentTimeMillis() - retentionMs

                        val selection = param.args[2]?.toString() ?: ""
                        val selectionArgs = param.args[3] as? Array<String>
                        val sortOrder = param.args[4]?.toString() ?: ""

                        // Inject timestamp filter if query asks for it
                        if (selection.contains("timestamp >= ?") && selectionArgs != null) {
                            var index = 0
                            // Optimized loop: Locate the '?' corresponding to timestamp
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

                        // SQL Injection for LIMIT
                        val newOrder = when {
                            sortOrder.contains("limit", ignoreCase = true) -> {
                                // Use the pre-compiled regex
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

    private fun setupDynamicHooks(context: Context, classLoader: ClassLoader) {
        // Use local val for prefs to prevent Context leak in closure
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            -1
        }

        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        var methodName = if (versionCode == cachedVersion) {
            cachePrefs.getString(KEY_METHOD_CONFIG, null)
        } else {
            null
        }

        // If not cached or version changed, run DexKit
        if (methodName == null) {
            try {
                // "use" block ensures DexKitBridge is closed immediately after search
                // preventing native memory leaks
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
            } catch (_: Throwable) {
                // Fail silently, don't crash Gboard
            }
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

                            if (name == "enable_clipboard_entity_extraction" ||
                                name == "enable_clipboard_query_refactoring"
                            ) {
                                param.result = false
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                // If hook fails (e.g. method signature changed), clear cache
                cachePrefs.edit().remove(KEY_METHOD_CONFIG).apply()
            }
        }
    }
}
