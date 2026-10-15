package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.tabs.TabLayout
import com.umbra.hooks.core.BaseDynamicHook
import org.luckypray.dexkit.DexKitBridge

@SuppressLint("NewApi")
class GrafikaHook : BaseDynamicHook() {

    override val targetPackages = setOf("org.picquantmedia.grafika")
    override val tag = "UMBRA-GRAFIKA"
    override val prefsCacheName = "umbra_grafika_cache"
    override val successToastMessage = "Umbra: Grafika optimized & patched. Please restart."

    @Volatile private var isUiTweaked = false

    companion object {
        private const val KEY_PREMIUM_TARGETS = "grafika_premium_targets"
        private const val KEY_PAIRIP_TARGET = "grafika_pairip_target"
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean {
        return cachePrefs.contains(KEY_PREMIUM_TARGETS)
    }

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val premiumTargets = mutableSetOf<String>()
        var pairipTarget: String? = null

        bridge.findMethod {
            matcher {
                name("nativeIsProActive")
                returnType("boolean")
            }
        }.firstOrNull()?.let { m ->
            premiumTargets.add(m.className)
            logInfo("[SEARCH] Found NativePremiumAccessManager: ${m.className}")
        }

        bridge.findClass {
            matcher {
                usingStrings("ITEM_ALREADY_OWNED", "ITEM_NOT_OWNED")
            }
        }.firstOrNull()?.let { c ->
            premiumTargets.add(c.name)
            logInfo("[SEARCH] Found BillingHelper: ${c.name}")
        }

        bridge.findClass {
            matcher {
                usingStrings("PAYWALL_INTENT")
            }
        }.firstOrNull()?.let { c ->
            pairipTarget = c.name
            logInfo("[SEARCH] Found Pairip LicenseClient: ${c.name}")
        }

        cachePrefs.edit()
            .putStringSet(KEY_PREMIUM_TARGETS, premiumTargets)
            .putString(KEY_PAIRIP_TARGET, pairipTarget)
            .apply()

        applyHooks(targetClassLoader, cachePrefs)
    }

    override fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences) {
        val premiumClasses = cachePrefs.getStringSet(KEY_PREMIUM_TARGETS, emptySet()) ?: emptySet()
        val pairipClass = cachePrefs.getString(KEY_PAIRIP_TARGET, null)

        var hookedPremiumMethods = 0
        for (className in premiumClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { m ->
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                        try {
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

        val targetPairip = pairipClass ?: "com.pairip.licensecheck.LicenseClient"
        try {
            val clazz = classLoader.loadClass(targetPairip)
            clazz.declaredMethods.forEach { m ->
                if (m.name.contains("checkLicense", ignoreCase = true) ||
                    m.name.contains("Paywall", ignoreCase = true) ||
                    m.name.contains("ErrorDialog", ignoreCase = true)) {
                    val hookHandle = xposedModule.hook(m).intercept { null }
                    registerHook(hookHandle)
                }
            }
            logInfo("[SUCCESS] Pairip integrity checks neutralized.")
        } catch (e: Exception) {
            logInfo("[WARN] Pairip LicenseClient not found. Assuming removed or fully obfuscated.")
        }
    }

    override fun onPackageLoaded(classLoader: ClassLoader) {
        hookActivityLifecycle(classLoader)
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            val hookHandle = xposedModule.hook(activityClass.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity

                if (!isUiTweaked && activity != null && activity.javaClass.name.endsWith("StartActivity")) {
                    val rootView = activity.window.decorView.rootView

                    val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            try {
                                if (!isUiTweaked) {
                                    applyFunctionalUITweaks(activity)
                                }
                            } catch (e: Throwable) {
                                logError("[WARN] Exception during UI tweaks", e)
                            } finally {
                                if (rootView.viewTreeObserver.isAlive) {
                                    rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                }
                            }
                        }
                    }
                    rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                }
                result
            }
            registerHook(hookHandle)
            logInfo("[SUCCESS] Activity lifecycle observation active with micro-optimization.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to attach to Activity lifecycle", t)
        }
    }

    private fun applyFunctionalUITweaks(activity: Activity) {
        val connectId = activity.resources.getIdentifier("label_connect_with_us", "id", activity.packageName)
        if (connectId != 0) {
            activity.findViewById<View>(connectId)?.let { labelView ->
                labelView.visibility = View.GONE

                val parent = labelView.parent as? ViewGroup
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child.javaClass.name.endsWith("HorizontalScrollView")) {
                            child.visibility = View.GONE
                        }
                    }
                    isUiTweaked = true
                    logInfo("[UI] Connect with us section and social buttons neutralized permanently.")
                }
            }
        }

        try {
            val tabFields = activity.javaClass.declaredFields.filter {
                it.type.name == "com.google.android.material.tabs.TabLayout"
            }
            for (field in tabFields) {
                field.isAccessible = true
                val tabLayout = field.get(activity) as? TabLayout
                if (tabLayout != null && tabLayout.tabCount >= 3) {
                    tabLayout.removeTabAt(2)
                    logInfo("[UI] Broken Account Tab neutralized.")
                }
            }
        } catch (e: Throwable) {
            logError("[WARN] Failed to neutralize Account Tab", e)
        }
    }
}
