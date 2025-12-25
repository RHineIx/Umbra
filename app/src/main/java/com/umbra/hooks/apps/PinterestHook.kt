package com.umbra.hooks.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.utils.AdSanitizer
import com.umbra.hooks.utils.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class PinterestHook {

    private val adResourceNames = listOf(
        "sba_gma",      // Google Ads
        "native_ad",    // Native Ads
        "promoted",     // Promoted Pins
        "sponsor",      // Sponsored Content
        "ads_container" // Generic Ad Containers
    )
    
    private val hookedClasses = HashSet<String>()

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        initJsonHook()
        initUIHook()
    }

    private fun initJsonHook() {
        try {
            XposedHelpers.findAndHookConstructor(JSONObject::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val jsonString = param.args[0] as? String ?: return

                    // PERFORMANCE OPTIMIZATION:
                    // 1. Length check: Feed responses are usually large (>1KB). 
                    //    Skip tiny JSONs (configs, analytics) instantly to save CPU.
                    if (jsonString.length < 500) return

                    // 2. Keyword check: If it doesn't contain "data" key, it's not a feed.
                    if (!jsonString.contains("\"data\"")) return

                    // Now delegate to AdSanitizer
                    if (AdSanitizer.shouldSanitize(jsonString)) {
                        try {
                            param.args[0] = AdSanitizer.cleanFeed(jsonString)
                        } catch (e: Exception) {
                            // Prevent crash if sanitization fails, just log it
                            XposedBridge.log("[UMBRA] JSON Sanitize Error: ${e.message}")
                        }
                    }
                }
            })
        } catch (_: Throwable) { }
    }

    private fun initUIHook() {
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater::class.java, "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    val id = param.args[0] as Int
                    
                    // PERFORMANCE OPTIMIZATION:
                    // If ID is NO_ID (-1), it has no resource name. 
                    // calling getResourceEntryName throws exception which is heavy.
                    if (id == View.NO_ID) return

                    val context = view.context

                    try {
                        // Safe resource lookup
                        val name = try {
                             context.resources.getResourceEntryName(id)
                        } catch (e: Exception) { return }

                        if (adResourceNames.any { name.contains(it, ignoreCase = true) }) {
                            ViewUtils.nukeView(view)
                            
                            // Also hide the root parent if possible to avoid empty gaps
                            val rootGroup = param.args[1] as? ViewGroup
                            if (rootGroup != null) {
                                ViewUtils.nukeView(rootGroup)
                                ViewUtils.hookOnMeasure(rootGroup::class.java, hookedClasses)
                            }
                        }
                    } catch (_: Throwable) { }
                }
            })
        } catch (_: Throwable) { }
    }
}
