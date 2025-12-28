package com.umbra.hooks.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.AdSanitizer
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PinterestHook : AppHook {

    override val targetPackages = setOf(
        "com.pinterest"
    )

    // UI Cache (Secondary Defense)
    private val idCache: MutableMap<Int, Boolean> = ConcurrentHashMap()
    private val hookedClasses = Collections.synchronizedSet(HashSet<String>())

    // Legacy keywords for UI fallback (just in case)
    private val adResourceNames = listOf(
        "sba_gma",
        "native_ad", 
        "promoted",
        "sponsor",
        "ads_container"
    )

    override fun isEnabled(prefs: XSharedPreferences): Boolean {
        return prefs.getBoolean(Constants.KEY_PINTEREST_ENABLED, true)
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Priority 1: Data Layer Hook (God Mode)
        initJsonHook()
        
        // Priority 2: UI Layer Hook (Cleanup / Fallback)
        initUIHook()
    }

    private fun initJsonHook() {
        try {
            // Hook JSONObject constructor to intercept ALL JSON parsing
            XposedHelpers.findAndHookConstructor(
                JSONObject::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val jsonString = param.args[0] as? String ?: return

                        // PERFORMANCE OPTIMIZATION:
                        // 1. Length check: Feed responses are usually large (>1KB).
                        //    Skip tiny JSONs (configs, analytics) instantly to save CPU.
                        if (jsonString.length < 500) return

                        // 2. Keyword check: If it doesn't contain "data" key, it's not a feed.
                        if (!jsonString.contains("\"data\"")) return

                        // 3. Delegate to AdSanitizer
                        // This checks for "is_promoted" inside the string
                        if (AdSanitizer.shouldSanitize(jsonString)) {
                            try {
                                val cleanJson = AdSanitizer.cleanFeed(jsonString)
                                // Replace the argument with the clean JSON
                                param.args[0] = cleanJson
                            } catch (e: Exception) {
                                XposedBridge.log("[UMBRA] JSON Sanitize Error: ${e.message}")
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun initUIHook() {
        try {
            XposedHelpers.findAndHookMethod(
                LayoutInflater::class.java,
                "inflate",
                Int::class.javaPrimitiveType,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.result as? View ?: return
                        val id = param.args[0] as Int

                        if (id == View.NO_ID) return

                        // Check Cache First
                        val isAd = idCache.getOrPut(id) {
                            checkIfAd(view, id)
                        }

                        if (isAd) {
                            handleAdView(view, param.args[1] as? ViewGroup)
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun checkIfAd(view: View, id: Int): Boolean {
        return try {
            val name = view.context.resources.getResourceEntryName(id)
            adResourceNames.any { name.contains(it, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    private fun handleAdView(view: View, parent: ViewGroup?) {
        ViewUtils.nukeView(view)
        if (parent != null) {
            ViewUtils.nukeView(parent)
            ViewUtils.hookOnMeasure(parent.javaClass, hookedClasses)
        }
    }
}
