package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.net.Uri
import android.app.Application
import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.umbra.hooks.core.BaseDynamicHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import android.content.SharedPreferences
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.util.HashSet

/** Adjusts Gboard's clipboard query limit and retention period. */
@SuppressLint("NewApi")
class GboardHook : BaseDynamicHook() {
    override val targetPackages = setOf(GBOARD_PACKAGE)
    override val tag = "UMBRA-GBOARD"
    override val prefsCacheName = "umbra_gboard_cache"
    override val successToastMessage = "Gboard clipboard limits are ready. Restart Gboard to apply."

    private val clipboardLimit by lazy {
        PrefsManager.getHookInt(
            xposedModule,
            PREF_CLIPBOARD_LIMIT,
            Constants.DEFAULT_GBOARD_CLIPBOARD_LIMIT,
        ).coerceIn(1, MAX_CLIPBOARD_LIMIT)
    }
    private val retentionDurationMillis by lazy {
        PrefsManager.getHookInt(
            xposedModule,
            PREF_RETENTION_DAYS,
            Constants.DEFAULT_GBOARD_CLIPBOARD_RETENTION_DAYS,
        ).coerceIn(1, MAX_RETENTION_DAYS).toLong() * MILLIS_PER_DAY
    }

    private val initialized = AtomicBoolean(false)
    private var providerHookInstalled = false
    private var flagsHookInstalled = false
    private var retentionHookInstalled = false
    private val withinCapacityLogged = AtomicBoolean(false)
    private val overCapacityLogged = AtomicBoolean(false)

    companion object {
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val CLIPBOARD_PROVIDER =
            "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        private const val KEY_READ_CONFIG_METHOD = "gboard_read_config_method"
        private const val KEY_RETENTION_METHOD = "gboard_retention_method"
        private const val PREF_CLIPBOARD_LIMIT = Constants.KEY_GBOARD_CLIPBOARD_LIMIT
        private const val PREF_RETENTION_DAYS = Constants.KEY_GBOARD_CLIPBOARD_RETENTION_DAYS
        internal const val CACHE_SCHEMA_KEY = "gboard_hook_schema"
        internal const val CACHE_SCHEMA = 2
        private const val TARGET_VERSION_KEY = "gboard_target_version"
        private val timestampFilter = Regex("\\btimestamp\\s*>=\\s*\\?", RegexOption.IGNORE_CASE)
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val MAX_CLIPBOARD_LIMIT = 1000
        private const val MAX_RETENTION_DAYS = 3650
    }

    // Install discovery before Application.onCreate, as the working reference does.
    // The shared base engine scans only sourceDir asynchronously and is unsuitable
    // for flags that Gboard reads and caches during startup. Other hooks keep that engine.
    override fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        xposedModule = module
        targetClassLoader = param.defaultClassLoader
        logsEnabled = PrefsManager.getHookInt(module, Constants.KEY_GLOBAL_LOGS, 0) == 1
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        registerHook(module.hook(attach).intercept { chain ->
            val result = chain.proceed()
            val context = chain.args.firstOrNull() as? Context
            if (context != null && initialized.compareAndSet(false, true)) {
                try {
                    initializeClipboardHooks(context)
                } catch (t: Throwable) {
                    initialized.set(false)
                    logError("[ERROR] Gboard clipboard initialization failed; discovery will be retried.", t)
                }
            }
            result
        })
        onPackageLoaded(targetClassLoader)
    }

    private fun initializeClipboardHooks(context: Context) {
        targetClassLoader = context.classLoader
        // Retry if the provider was not yet resolvable at package-load time.
        if (!providerHookInstalled) hookClipboardProvider(targetClassLoader)
        val cache = context.getSharedPreferences(prefsCacheName, Context.MODE_PRIVATE)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        logInfo("[CONFIG] Gboard version=${info.versionName} ($version), clipboardLimit=$clipboardLimit, " +
            "retentionDays=${retentionDurationMillis / MILLIS_PER_DAY}.")
        val cachedRead = cache.getString(KEY_READ_CONFIG_METHOD, null)?.let { resolveMethod(targetClassLoader, it) }
        val cachedRetention = cache.getString(KEY_RETENTION_METHOD, null)?.let { resolveMethod(targetClassLoader, it) }
        if (cache.getLong(TARGET_VERSION_KEY, -1) == version && hasValidCache(cache) &&
            cachedRead != null && cachedRetention != null) {
            applyHooks(targetClassLoader, cache)
        } else {
            cache.edit().remove(CACHE_SCHEMA_KEY).apply()
            try {
                System.loadLibrary("dexkit")
            } catch (_: UnsatisfiedLinkError) {
                System.load(File(xposedModule.moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").absolutePath)
            }
            // Includes split APKs and in-memory DEX exposed by the target's loader.
            DexKitBridge.create(targetClassLoader, true).use { bridge ->
                performSearchSynchronous(bridge, cache)
            }
        }
        if (flagsHookInstalled && retentionHookInstalled) {
            cache.edit().putInt(CACHE_SCHEMA_KEY, CACHE_SCHEMA).putLong(TARGET_VERSION_KEY, version).apply()
            logInfo("[SUCCESS] Gboard clipboard flags and retention hooks are installed.")
        } else {
            // Empty or failed results must never permanently suppress rediscovery.
            cache.edit().remove(CACHE_SCHEMA_KEY).apply()
            initialized.set(false)
            logInfo("[WARN] Gboard discovery is incomplete; the next startup will retry.")
        }
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean =
        cachePrefs.getInt(CACHE_SCHEMA_KEY, 0) == CACHE_SCHEMA &&
            !cachePrefs.getString(KEY_READ_CONFIG_METHOD, null).isNullOrBlank() &&
            !cachePrefs.getString(KEY_RETENTION_METHOD, null).isNullOrBlank()

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val readConfigMethod =
            bridge.findMethod {
                matcher {
                    usingStrings("Invalid flag: ")
                    returnType("java.lang.Object")
                }
            }.map { it.toDexMethod() }
                .filter { it.paramTypeNames.isEmpty() }
                .singleOrNull()?.serialize().orEmpty()

        val retentionMethod =
            bridge.findMethod {
                matcher {
                    usingStrings("Failed to parse retention hours")
                }
            }.map { it.toDexMethod() }
                .filter { it.returnTypeName == "long" || it.returnTypeName == "java.lang.Long" }
                .singleOrNull()?.serialize().orEmpty()

        cachePrefs.edit()
            .putString(KEY_READ_CONFIG_METHOD, readConfigMethod)
            .putString(KEY_RETENTION_METHOD, retentionMethod)
            .apply()

        if (readConfigMethod.isEmpty()) {
            logInfo("[WARN] Could not identify Gboard's clipboard flag reader.")
        }
        if (retentionMethod.isEmpty()) {
            logInfo("[WARN] Could not identify Gboard's clipboard retention method.")
        }

        applyHooks(targetClassLoader, cachePrefs)
    }

    override fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences) {
        cachePrefs.getString(KEY_READ_CONFIG_METHOD, null)
            ?.takeIf(String::isNotEmpty)
            ?.let { serialized ->
                resolveMethod(classLoader, serialized)?.let { method ->
                    if (!flagsHookInstalled) hookClipboardFlags(method)
                }
                    ?: logInfo("[WARN] Cached clipboard flag method could not be resolved.")
            }

        cachePrefs.getString(KEY_RETENTION_METHOD, null)
            ?.takeIf(String::isNotEmpty)
            ?.let { serialized ->
                resolveMethod(classLoader, serialized)?.let { method ->
                    if (!retentionHookInstalled) hookRetentionPeriod(method)
                }
                    ?: logInfo("[WARN] Cached clipboard retention method could not be resolved.")
            }
    }

    override fun onPackageLoaded(classLoader: ClassLoader) {
        hookClipboardProvider(classLoader)
        hookClipboardTimestampSetSize()
    }

    private fun hookClipboardProvider(classLoader: ClassLoader) {
        try {
            val provider = classLoader.loadClass(CLIPBOARD_PROVIDER)
            val queryMethod = provider.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
            )

            registerHook(xposedModule.hook(queryMethod).intercept { chain ->
                val args = chain.args.toTypedArray()
                try {
                    rewriteClipboardQuery(args, clipboardLimit, retentionDurationMillis, System.currentTimeMillis())
                } catch (t: Throwable) {
                    logError("[WARN] Could not adjust a Gboard clipboard query.", t)
                }
                chain.proceed(args)
            })
            providerHookInstalled = true
            logInfo("[SUCCESS] Gboard clipboard provider query hook installed.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Gboard's clipboard content provider.", t)
        }
    }

    private fun hookClipboardFlags(method: Method) {
        try {
            registerHook(xposedModule.hook(method).intercept { chain ->
                val flagName = chain.thisObject?.let { readField(it, "a") }?.toString()
                if (flagName == "enable_clipboard_entity_extraction" ||
                    flagName == "enable_clipboard_query_refactoring"
                ) {
                    logInfo("[CONFIG] Disabled Gboard clipboard flag $flagName.")
                    false
                } else {
                    chain.proceed()
                }
            })
            flagsHookInstalled = true
            logInfo("[SUCCESS] Gboard clipboard feature-flag hook installed.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Gboard's clipboard flag reader.", t)
        }
    }

    private fun hookRetentionPeriod(method: Method) {
        try {
            registerHook(xposedModule.hook(method).intercept { chain ->
                chain.proceed()
                retentionDurationMillis
            })
            retentionHookInstalled = true
            logInfo("[SUCCESS] Gboard clipboard retention hook installed.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Gboard's clipboard retention method.", t)
        }
    }

    /** Translate the configured capacity to Gboard's built-in five-entry size threshold. */
    private fun hookClipboardTimestampSetSize() {
        try {
            val sizeMethod = HashSet::class.java.getDeclaredMethod("size")
            registerHook(xposedModule.hook(sizeMethod).intercept { chain ->
                // Use the original size instead of reflecting into Android's HashSet backing map.
                val actualSize = chain.proceed() as Int
                val set = chain.thisObject as? HashSet<*>
                val firstElement = set?.firstOrNull()
                if (firstElement?.javaClass?.name == "j$.time.Instant") {
                    val reportedSize = clipboardTimestampSize(actualSize, clipboardLimit)
                    val logged = if (actualSize > clipboardLimit) overCapacityLogged else withinCapacityLogged
                    if (logsEnabled && logged.compareAndSet(false, true)) {
                        logInfo("[CAPACITY] actualSize=$actualSize, configuredLimit=$clipboardLimit, reportedSize=$reportedSize.")
                    }
                    reportedSize
                } else {
                    actualSize
                }
            })
            logInfo("[SUCCESS] Gboard clipboard timestamp-set compatibility hook installed.")
        } catch (t: Throwable) {
            logError("[WARN] Could not install the Gboard timestamp-set compatibility hook.", t)
        }
    }

    internal fun clipboardTimestampSize(actualSize: Int, limit: Int): Int = when {
        actualSize == 0 -> 0
        actualSize <= limit -> 5
        // Returning the real size (2..5) here leaves Gboard below its overflow threshold,
        // even when a user-selected limit of 1..4 has already been exceeded.
        else -> maxOf(6, actualSize)
    }

    /** Mutate only clipboard query arguments, preserving the caller's selection array. */
    internal fun rewriteClipboardQuery(args: Array<Any?>, limit: Int, retentionMillis: Long, now: Long) {
        val selection = args.getOrNull(2) as? String
        val selectionArgs = args.getOrNull(3) as? Array<*>
        val marker = selection?.let { timestampFilter.find(it) }
        if (marker != null && selectionArgs != null) {
            val index = selection.take(marker.range.first).count { it == '?' }
            if (index in selectionArgs.indices) {
                val updated = Array(selectionArgs.size) { selectionArgs[it] as? String }
                updated[index] = (now - retentionMillis).toString()
                args[3] = updated
            }
        }
        if ((args.getOrNull(4) as? String)?.trim()?.equals("timestamp DESC limit 5", ignoreCase = true) == true) {
            args[4] = "timestamp DESC limit ${limit.coerceIn(1, MAX_CLIPBOARD_LIMIT)}"
        }
    }

    internal fun resolveMethod(classLoader: ClassLoader, serialized: String): Method? {
        return try {
            val dexMethod = DexMethod(serialized)
            val parameterTypes = mutableListOf<Class<*>>()
            for (typeName in dexMethod.paramTypeNames) {
                parameterTypes += resolveType(typeName, classLoader) ?: return null
            }
            classLoader.loadClass(dexMethod.className)
                .getDeclaredMethod(dexMethod.name, *parameterTypes.toTypedArray())
        } catch (t: Throwable) {
            logError("[WARN] Failed to resolve cached Gboard method.", t)
            null
        }
    }

    private fun resolveType(typeName: String, classLoader: ClassLoader): Class<*>? {
        val primitive = when (typeName) {
            "boolean" -> Boolean::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            "int" -> Int::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            else -> null
        }
        if (primitive != null) return primitive
        if (typeName == "void") return Void.TYPE
        if (typeName.startsWith("[")) return Class.forName(typeName, false, classLoader)
        if (typeName.endsWith("[]")) {
            val component = resolveType(typeName.removeSuffix("[]"), classLoader) ?: return null
            return ReflectArray.newInstance(component, 0).javaClass
        }
        return Class.forName(typeName, false, classLoader)
    }

    private fun readField(instance: Any, name: String): Any? {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(name)
                field.isAccessible = true
                return field.get(instance)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }
}
