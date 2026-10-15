package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.net.Uri
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

    companion object {
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val CLIPBOARD_PROVIDER =
            "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        private const val KEY_READ_CONFIG_METHOD = "gboard_read_config_method"
        private const val KEY_RETENTION_METHOD = "gboard_retention_method"
        private const val PREF_CLIPBOARD_LIMIT = Constants.KEY_GBOARD_CLIPBOARD_LIMIT
        private const val PREF_RETENTION_DAYS = Constants.KEY_GBOARD_CLIPBOARD_RETENTION_DAYS
        private const val TIMESTAMP_FILTER = "timestamp >= ?"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val MAX_CLIPBOARD_LIMIT = 1000
        private const val MAX_RETENTION_DAYS = 3650
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean =
        cachePrefs.contains(KEY_READ_CONFIG_METHOD) && cachePrefs.contains(KEY_RETENTION_METHOD)

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val readConfigMethod =
            bridge.findMethod {
                matcher {
                    usingStrings("Invalid flag: ")
                    returnType("java.lang.Object")
                }
            }.singleOrNull()?.toDexMethod()?.serialize().orEmpty()

        val retentionMethod =
            bridge.findMethod {
                matcher {
                    usingStrings("Failed to parse retention hours")
                }
            }.singleOrNull()?.toDexMethod()?.serialize().orEmpty()

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
                resolveMethod(classLoader, serialized)?.let(::hookClipboardFlags)
                    ?: logInfo("[WARN] Cached clipboard flag method could not be resolved.")
            }

        cachePrefs.getString(KEY_RETENTION_METHOD, null)
            ?.takeIf(String::isNotEmpty)
            ?.let { serialized ->
                resolveMethod(classLoader, serialized)?.let(::hookRetentionPeriod)
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
                    val selection = args.getOrNull(2) as? String
                    val selectionArgs = args.getOrNull(3) as? Array<*>
                    val timestampArgument = selection?.let(::timestampArgumentIndex) ?: -1

                    if (selectionArgs != null && timestampArgument in selectionArgs.indices) {
                        val updatedArgs = arrayOfNulls<String>(selectionArgs.size)
                        for (index in selectionArgs.indices) {
                            updatedArgs[index] = selectionArgs[index] as? String
                        }
                        updatedArgs[timestampArgument] =
                            (System.currentTimeMillis() - retentionDurationMillis).toString()
                        args[3] = updatedArgs
                    }

                    val sortOrder = args.getOrNull(4) as? String
                    if (sortOrder?.trim()?.equals("timestamp DESC limit 5", ignoreCase = true) == true) {
                        args[4] = "timestamp DESC limit $clipboardLimit"
                    }
                } catch (t: Throwable) {
                    logError("[WARN] Could not adjust a Gboard clipboard query; leaving its arguments unchanged.", t)
                }
                chain.proceed(args)
            })
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
            logInfo("[SUCCESS] Gboard clipboard retention hook installed.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Gboard's clipboard retention method.", t)
        }
    }

    /** The reference hook uses this set-size workaround for Gboard's desugared Instant set. */
    private fun hookClipboardTimestampSetSize() {
        try {
            val sizeMethod = HashSet::class.java.getDeclaredMethod("size")
            registerHook(xposedModule.hook(sizeMethod).intercept { chain ->
                val set = chain.thisObject as? HashSet<*>
                val firstElement = set?.firstOrNull()
                val backingMap = set?.let { readField(it, "map") as? Map<*, *> }
                if (firstElement?.javaClass?.name == "j$.time.Instant" &&
                    backingMap != null && backingMap.size <= clipboardLimit
                ) {
                    5
                } else {
                    chain.proceed()
                }
            })
            logInfo("[SUCCESS] Gboard clipboard timestamp-set compatibility hook installed.")
        } catch (t: Throwable) {
            logError("[WARN] Could not install the Gboard timestamp-set compatibility hook.", t)
        }
    }

    private fun timestampArgumentIndex(selection: String): Int {
        val markerIndex = selection.indexOf(TIMESTAMP_FILTER, ignoreCase = true)
        if (markerIndex < 0) return -1
        return selection.substring(0, markerIndex).count { it == '?' }
    }

    private fun resolveMethod(classLoader: ClassLoader, serialized: String): Method? {
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
