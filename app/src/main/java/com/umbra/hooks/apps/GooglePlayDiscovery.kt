package com.umbra.hooks.apps

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/** Semantic discovery of the account manager, without logging account data. */
internal object GooglePlayDiscovery {
    private const val TAG = "UMBRA-PLAY"
    private const val STORE = "com.android.vending"
    private const val REVIEWED = "54.4.11-34 [0] [PR] 1025789771"
    private const val CACHE = "umbra_play_mapping"
    private const val SCHEMA = 1

    private fun versionKey(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(STORE, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$SCHEMA:$code:${info.versionName}:${info.lastUpdateTime}"
    }.getOrNull()

    @JvmStatic fun isReviewedVersion(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(STORE, 0).versionName == REVIEWED }.getOrDefault(false)

    @JvmStatic fun cached(context: Context): String? {
        val key = versionKey(context) ?: return null
        val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        return prefs.getString("mapping", null)?.takeIf { prefs.getString("version", null) == key }
    }

    @JvmStatic fun clear(context: Context) {
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic fun remember(context: Context, mapping: String) {
        val key = versionKey(context) ?: return
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
            .putString("version", key).putString("mapping", mapping).apply()
    }

    @JvmStatic fun discover(module: XposedModule, loader: ClassLoader, context: Context): String? {
        return try {
            try {
                System.loadLibrary("dexkit")
            } catch (_: UnsatisfiedLinkError) {
                System.load(File(module.moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").absolutePath)
            }
            // First scan the base APK (all eight DEX files in the reviewed build).
            // Only scan the loader if the base does not contain both semantic anchors.
            val result = DexKitBridge.create(context.applicationInfo.sourceDir).use { scan(it) }
                ?: DexKitBridge.create(loader, true).use { scan(it) }
            module.log(Log.INFO, TAG, if (result == null) "DexKit: no unique validated anchor pair"
                else "DexKit account manager candidate: $result")
            result
        } catch (failure: Throwable) {
            module.log(Log.WARN, TAG, "DexKit discovery failed: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun scan(bridge: DexKitBridge): String? {
        val current = bridge.findMethod {
            matcher {
                usingStrings("is not valid, selecting first available account:")
                returnType("android.accounts.Account")
            }
        }.map { it.toDexMethod() }
            .filter { it.name == "b" && it.paramTypeNames.isEmpty() }
            .map { it.className }.toSet()
        val lists = bridge.findMethod {
            matcher {
                usingStrings("Lazy initialization of accounts cache", "Accounts cache should not be null after initialization")
                returnType("java.util.List")
            }
        }.map { it.toDexMethod() }
            .filter { it.name == "i" && it.paramTypeNames.isEmpty() }
            .map { it.className }.toSet()
        // Require independent anchor matches in the same single class.
        // Reflection in GooglePlayHook then checks the entire signature family.
        return current.intersect(lists).singleOrNull()
    }
}
