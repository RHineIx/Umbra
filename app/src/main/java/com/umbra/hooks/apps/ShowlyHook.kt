package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.umbra.hooks.core.BaseDynamicHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@SuppressLint("NewApi")
class ShowlyHook : BaseDynamicHook() {

    override val targetPackages = setOf("com.michaldrabik.showly2")
    override val tag = "UMBRA-SHOWLY"
    override val prefsCacheName = "umbra_showly_cache"
    override val successToastMessage = "Umbra: Showly optimized & patched. Please restart."

    companion object {
        private const val KEY_PREMIUM_TARGETS = "showly_premium_targets"
        private const val KEY_SYNC_TARGETS = "showly_sync_targets"
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean {
        return cachePrefs.contains(KEY_PREMIUM_TARGETS) && cachePrefs.contains(KEY_SYNC_TARGETS)
    }

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val premiumTargets = mutableSetOf<String>()
        val syncTargets = mutableSetOf<String>()

        bridge.findClass {
            matcher {
                usingStrings("KEY_PREMIUM")
            }
        }.forEach { c ->
            premiumTargets.add(c.name)
            logInfo("[SEARCH] Found Premium Target: ${c.name}")
        }

        bridge.findMethod {
            matcher {
                name("onStart")
                usingStrings("settingsRepository")
            }
        }.forEach { m ->
            syncTargets.add("${m.className}|${m.methodName}")
            logInfo("[SEARCH] Found Lifecycle Sync Method: ${m.className}.${m.methodName}")
        }

        cachePrefs.edit()
            .putStringSet(KEY_PREMIUM_TARGETS, premiumTargets)
            .putStringSet(KEY_SYNC_TARGETS, syncTargets)
            .apply()

        applyHooks(targetClassLoader, cachePrefs)
    }

    override fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences) {
        val premiumClasses = cachePrefs.getStringSet(KEY_PREMIUM_TARGETS, emptySet()) ?: emptySet()
        val syncTargets = cachePrefs.getStringSet(KEY_SYNC_TARGETS, emptySet()) ?: emptySet()

        var hookedPremiumMethods = 0
        for (className in premiumClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { m ->
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty() && !Modifier.isAbstract(m.modifiers)) {
                        try {
                            // Register Hook for API 102 Detach
                            val hookHandle = xposedModule.hook(m).intercept { true }
                            registerHook(hookHandle)
                            hookedPremiumMethods++
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                logError("[WARN] Failed to hook premium class: $className", e)
            }
        }
        logInfo("[SUCCESS] Aggressive Premium state forced. Hooked $hookedPremiumMethods methods.")

        for (target in syncTargets) {
            val parts = target.split("|")
            if (parts.size == 2) {
                try {
                    val clazz = classLoader.loadClass(parts[0])
                    clazz.declaredMethods.forEach { m ->
                        if (m.name == parts[1]) {
                            // Register Hook for API 102 Detach
                            val hookHandle = xposedModule.hook(m).intercept { null }
                            registerHook(hookHandle)
                            logInfo("[SUCCESS] Lifecycle Sync execution neutralized for ${parts[0]}")
                        }
                    }
                } catch (e: Exception) {
                    logError("[WARN] Failed to neutralize sync on ${parts[0]}", e)
                }
            }
        }
    }
}
