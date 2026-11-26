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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import io.github.libxposed.api.XposedInterface
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
    private val dynamicReady = AtomicBoolean(false)
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
        internal const val CACHE_SCHEMA = GboardRules.SCHEMA
        private const val TARGET_BUILD_KEY = "gboard_target_build"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val MAX_CLIPBOARD_LIMIT = GboardRules.MAX_CLIPBOARD_LIMIT
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
            if (context?.packageName == GBOARD_PACKAGE && initialized.compareAndSet(false, true)) {
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
        // Exact content identity (including splits). Hash once per process,
        // never on clipboard queries or key presses. An unreadable APK disables
        // persistent caching, not discovery for the current process.
        val buildKey = runCatching {
            GboardRules.buildKey(version, info.versionName, info.lastUpdateTime,
                GboardRules.sha256(File(context.applicationInfo.sourceDir)),
                context.applicationInfo.splitSourceDirs.orEmpty().map { GboardRules.sha256(File(it)) })
        }.getOrNull()
        if (buildKey != null && cache.getString(TARGET_BUILD_KEY, null) == buildKey && hasValidCache(cache)) {
            applyHooks(targetClassLoader, cache)
            if (dynamicReady.get()) {
                logInfo("[CACHE] Validated exact-build Gboard methods installed.")
                return
            }
        }
        // A cached method can resolve but still fail to hook. Clear the stale
        // pair and rediscover immediately instead of waiting for another launch.
        clearCache(cache)
        try {
            System.loadLibrary("dexkit")
        } catch (_: UnsatisfiedLinkError) {
            System.load(File(xposedModule.moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").absolutePath)
        }
        DexKitBridge.create(targetClassLoader, true).use { bridge ->
            performSearchSynchronous(bridge, cache)
        }
        if (dynamicReady.get()) {
            if (buildKey != null) {
                cache.edit().putInt(CACHE_SCHEMA_KEY, CACHE_SCHEMA).putString(TARGET_BUILD_KEY, buildKey).apply()
            } else clearCache(cache)
            logInfo("[SUCCESS] Gboard clipboard flags and retention hooks are installed.")
        } else {
            clearCache(cache)
            initialized.set(false)
            logInfo("[WARN] Incomplete dynamic discovery; original flag/retention behavior retained.")
        }
    }

    private fun clearCache(cache: SharedPreferences) {
        cache.edit().remove(CACHE_SCHEMA_KEY).remove(TARGET_BUILD_KEY)
            .remove(KEY_READ_CONFIG_METHOD).remove(KEY_RETENTION_METHOD).apply()
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean =
        cachePrefs.getInt(CACHE_SCHEMA_KEY, 0) == CACHE_SCHEMA &&
            !cachePrefs.getString(TARGET_BUILD_KEY, null).isNullOrBlank() &&
            !cachePrefs.getString(KEY_READ_CONFIG_METHOD, null).isNullOrBlank() &&
            !cachePrefs.getString(KEY_RETENTION_METHOD, null).isNullOrBlank()

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val read = bridge.findMethod {
            matcher { usingStrings("Invalid flag: "); returnType("java.lang.Object") }
        }.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers) && it.paramTypeNames.isEmpty()
        }.singleOrNull()?.toDexMethod()?.serialize()

        val retention = bridge.findMethod {
            matcher { usingStrings("Failed to parse retention hours") }
        }.filter { it.isMethod && !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers) &&
            (it.returnTypeName == "long" || it.returnTypeName == "java.lang.Long")
        }.singleOrNull()?.toDexMethod()?.serialize()

        if (read == null || retention == null) {
            logInfo("[WARN] Gboard flag reader or retention fingerprint absent or ambiguous.")
            return
        }
        val readMethod = resolveMethod(targetClassLoader, read) ?: return
        val retentionMethod = resolveMethod(targetClassLoader, retention) ?: return
        if (installDynamicPair(readMethod, retentionMethod)) {
            // The schema/build marker is written only after complete installation.
            cachePrefs.edit().putString(KEY_READ_CONFIG_METHOD, read)
                .putString(KEY_RETENTION_METHOD, retention).apply()
        }
    }

    override fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences) {
        if (dynamicReady.get()) return
        val read = cachePrefs.getString(KEY_READ_CONFIG_METHOD, null) ?: return
        val retention = cachePrefs.getString(KEY_RETENTION_METHOD, null) ?: return
        val readMethod = resolveMethod(classLoader, read) ?: return
        val retentionMethod = resolveMethod(classLoader, retention) ?: return
        installDynamicPair(readMethod, retentionMethod)
    }

    // Field names are deliberately ignored. Only instance String/Object/enum
    // fields in the discovered flag reader's hierarchy can carry a flag name.
    // Compute reflection candidates once at installation, not on every read.
    private fun identityFields(owner: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) &&
                    (field.type == String::class.java || field.type == Any::class.java ||
                        field.type == CharSequence::class.java || field.type.isEnum)) {
                    field.isAccessible = true
                    fields.add(field)
                    if (fields.size > 64) return emptyList()
                }
            }
            current = current.superclass
        }
        return fields
    }

    private fun installDynamicPair(read: Method, retention: Method): Boolean {
        if (dynamicReady.get()) return true
        val pending = mutableListOf<XposedInterface.HookHandle>()
        return try {
            require(read.parameterCount == 0 && read.returnType == Any::class.java &&
                !Modifier.isStatic(read.modifiers) && !Modifier.isAbstract(read.modifiers) &&
                !Modifier.isNative(read.modifiers))
            require((retention.returnType == Long::class.javaPrimitiveType ||
                retention.returnType == Long::class.javaObjectType) &&
                !Modifier.isAbstract(retention.modifiers) && !Modifier.isNative(retention.modifiers))
            val fields = identityFields(read.declaringClass)
            require(fields.isNotEmpty()) { "No bounded flag identity fields" }
            pending.add(xposedModule.hook(read).intercept { chain ->
                if (!dynamicReady.get()) return@intercept chain.proceed()
                val instance = chain.thisObject
                val flag = if (instance == null) null else runCatching {
                    GboardRules.flagName(fields.map { it.get(instance) })
                }.getOrNull()
                if (flag != null) false else chain.proceed()
            })
            pending.add(xposedModule.hook(retention).intercept { chain ->
                val original = chain.proceed()
                if (dynamicReady.get()) retentionDurationMillis else original
            })
            pending.forEach(::registerHook)
            dynamicReady.set(true)
            true
        } catch (t: Throwable) {
            dynamicReady.set(false)
            pending.asReversed().forEach { runCatching { it.unhook() } }
            logError("[WARN] Dynamic Gboard pair rejected; partial hooks rolled back.", t)
            false
        }
    }

    override fun detachAll() {
        dynamicReady.set(false)
        super.detachAll()
    }

    override fun onPackageLoaded(classLoader: ClassLoader) {
        hookClipboardProvider(classLoader)
        hookClipboardTimestampSetSize()
        GboardSpacebarHook(xposedModule, logsEnabled).install()?.let(::registerHook)
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

    /** Translate the configured capacity to Gboard's built-in five-entry size threshold. */
    private fun hookClipboardTimestampSetSize() {
        try {
            val sizeMethod = HashSet::class.java.getDeclaredMethod("size")
            registerHook(xposedModule.hook(sizeMethod).intercept { chain ->
                // Use the original size instead of reflecting into Android's HashSet backing map.
                val actualSize = chain.proceed() as Int
                // The framework hook runs for many unrelated collections. Do
                // not read preferences or traverse large sets on those calls.
                if (actualSize <= 0 || actualSize > MAX_CLIPBOARD_LIMIT)
                    return@intercept actualSize
                val set = chain.thisObject as? HashSet<*> ?: return@intercept actualSize
                val first = runCatching { set.iterator().let { if (it.hasNext()) it.next() else null } }.getOrNull()
                if (first?.javaClass?.name != "j$.time.Instant") return@intercept actualSize
                val reportedSize = clipboardTimestampSize(actualSize, clipboardLimit)
                if (reportedSize == actualSize) return@intercept actualSize
                // Require a homogeneous timestamp set, not just its first item.
                val timestampSet = runCatching {
                    set.all { it?.javaClass?.name == "j$.time.Instant" }
                }.getOrDefault(false)
                if (timestampSet) {
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

    internal fun clipboardTimestampSize(actualSize: Int, limit: Int): Int =
        GboardRules.timestampSize(actualSize, limit)

    internal fun rewriteClipboardQuery(args: Array<Any?>, limit: Int, retentionMillis: Long, now: Long) =
        GboardRules.rewriteQuery(args, limit, retentionMillis, now)

    internal fun resolveMethod(classLoader: ClassLoader, serialized: String): Method? {
        return try {
            val dexMethod = DexMethod(serialized)
            val parameterTypes = mutableListOf<Class<*>>()
            for (typeName in dexMethod.paramTypeNames) {
                parameterTypes += resolveType(typeName, classLoader) ?: return null
            }
            val method = classLoader.loadClass(dexMethod.className)
                .getDeclaredMethod(dexMethod.name, *parameterTypes.toTypedArray())
            val returns = resolveType(dexMethod.returnTypeName, classLoader) ?: return null
            method.takeIf { it.returnType == returns }
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

}
