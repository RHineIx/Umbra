package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

class PydroidHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-PYDROID]"
        private const val NAV_VIEW_CLASS = "com.google.android.material.navigation.NavigationView"
        private const val TARGET_VIEW_NAME = "story_circle"
        
        // Preference Key (Must match your SwitchPreference key in XML)
        private const val KEY_LOGS = "pydroid_logs" 
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    @Volatile private var logsEnabled = false

    private fun log(message: String) {
        if (logsEnabled) {
            XposedBridge.log("$TAG $message")
        }
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Proactive UI Nuke (Layout Inflation Hook) - ZERO Flicker
        hookLayoutInflater(lpparam.classLoader)

        // 2. Logic Hook (Application Attach)
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args[0] as Context
                        
                        // Initialize User Preferences
                        refreshConfig(context)

                        if (logsEnabled) log("Context attached. Starting DexKit logic...")
                        
                        // Execute Logic Hook (Ads & Premium)
                        executeDexKitHooks(context.classLoader)
                        
                        // Backup UI Hook (Legacy safety net)
                        hideStoryCircleFast(context.classLoader)

                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG Fatal Error in attach: ${t.message}")
                    }
                }
            }
        )
    }

    private fun refreshConfig(context: Context) {
        // Reads the 'pydroid_logs' boolean from the module's preferences
        logsEnabled = PrefsManager.getRemoteInt(context, KEY_LOGS, 0) == 1
    }

    /**
     * The Nuclear Option: Intercepts XML inflation.
     * Finds 'story_circle' the moment it is created in memory, BEFORE it is added to the window.
     */
    private fun hookLayoutInflater(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                LayoutInflater::class.java,
                "inflate",
                Int::class.javaPrimitiveType,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rootView = param.result as? View ?: return
                        val context = rootView.context ?: return

                        // Fast ID lookup
                        val resId = context.resources.getIdentifier(TARGET_VIEW_NAME, "id", context.packageName)
                        
                        if (resId != 0) {
                            val targetView = rootView.findViewById<View>(resId)
                            if (targetView != null) {
                                nukeView(targetView)
                                log("Proactively nuked story_circle during inflation.")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            if (logsEnabled) log("Failed to hook LayoutInflater: $t")
        }
    }

    private fun executeDexKitHooks(classLoader: ClassLoader) {
        try {
            // Safe Native Load
            System.loadLibrary("dexkit")
            
            DexKitBridge.create(classLoader, false).use { bridge ->
                
                val matchers = bridge.findMethod {
                    matcher {
                        paramTypes(NAV_VIEW_CLASS)
                        returnType("void")
                    }
                }

                if (matchers.isEmpty()) {
                    log("Fatal: Could not find anchor method. Hook aborted.")
                    return
                }

                log("Found ${matchers.size} candidates. Filtering...")

                for (methodData in matchers) {
                    val className = methodData.className
                    
                    // Filter out UI classes
                    if (className.startsWith("com.google.") || 
                        className.startsWith("android.") || 
                        className.contains("Activity") || 
                        className.contains("Fragment")) {
                        continue
                    }

                    hookMainControllerClass(classLoader, methodData)
                }
            }
        } catch (t: Throwable) {
            // Keep errors visible for debugging
            XposedBridge.log("$TAG DexKit Init Failed: $t")
        }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, anchorMethod: MethodData) {
        val className = anchorMethod.className
        val navMethodName = anchorMethod.methodName 

        try {
            val clazz = XposedHelpers.findClass(className, classLoader)

            // Hook 1: Disable Ad/Menu Logic
            XposedHelpers.findAndHookMethod(
                clazz,
                navMethodName,
                NAV_VIEW_CLASS,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null // Disable execution
                    }
                }
            )
            log("Hooked Ad/Menu handler: $className.$navMethodName")

            // Hook 2: Enable Premium (Boolean Getters)
            val methods = clazz.declaredMethods
            for (method in methods) {
                if (method.parameterTypes.isEmpty() &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    !Modifier.isAbstract(method.modifiers)
                ) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz,
                            method.name,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    param.result = true
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking class $className: $t")
        }
    }

    // --- Fast UI Cleanup (Backup) ---
    private fun hideStoryCircleFast(classLoader: ClassLoader) {
        val activityClass = "android.app.Activity"
        val nukeHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                nukeStoryCircleInternal(activity)
            }
        }
        try {
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", Int::class.javaPrimitiveType, nukeHook)
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", View::class.java, nukeHook)
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", View::class.java, ViewGroup.LayoutParams::class.java, nukeHook)
        } catch (_: Throwable) {}
    }

    private fun nukeStoryCircleInternal(activity: android.app.Activity) {
        try {
            val resId = activity.resources.getIdentifier(TARGET_VIEW_NAME, "id", activity.packageName)
            if (resId != 0) {
                val view = activity.findViewById<View>(resId)
                nukeView(view)
            }
        } catch (_: Throwable) {}
    }

    private fun nukeView(view: View?) {
        if (view == null) return
        try {
            if (view.visibility == View.GONE && view.layoutParams?.height == 0) return

            view.visibility = View.GONE
            view.alpha = 0f 

            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                }
                view.layoutParams = params
            }
            view.setOnClickListener(null)
            view.isClickable = false
        } catch (_: Throwable) {}
    }
}