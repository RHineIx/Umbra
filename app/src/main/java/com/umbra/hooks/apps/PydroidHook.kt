package com.umbra.hooks.apps

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import de.robv.android.xposed.XC_MethodHook
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
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    @Volatile private var logsEnabled = false

    private fun log(message: String) {
        if (logsEnabled) {
            XposedBridge.log("$TAG $message")
        }
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook Application.attach to initialize configs and DexKit
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args[0] as Context
                        refreshConfig(context)

                        if (logsEnabled) log("Context attached. Initializing...")

                        // 1. Initialize DexKit Hooks (Premium/Ads Logic)
                        executeDexKitHooks(context.classLoader)

                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG Fatal Error in attach: ${t.message}")
                    }
                }
            }
        )

        // 2. Performance-Friendly UI Hook (Activity Lifecycle)
        hookActivityLifecycle(lpparam.classLoader)
    }

    private fun refreshConfig(context: Context) {
        // Use Global Logs Key
        logsEnabled = PrefsManager.getRemoteInt(context, Constants.KEY_GLOBAL_LOGS, 0) == 1
    }

    /**
     * Optimized UI Hook:
     * Hooks 'onPostCreate' of Activity. This guarantees the view hierarchy is built.
     * Zero impact on layout inflation performance.
     */
    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            
            XposedBridge.hookAllMethods(
                activityClass,
                "onPostCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        nukeStoryCircleInternal(activity)
                    }
                }
            )

            // Redundancy: Also check onResume in case the view is recreated dynamically
            XposedBridge.hookAllMethods(
                activityClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        nukeStoryCircleInternal(activity)
                    }
                }
            )

        } catch (t: Throwable) {
            if (logsEnabled) log("Failed to hook Activity Lifecycle: $t")
        }
    }

    private fun nukeStoryCircleInternal(activity: Activity) {
        try {
            val resId = activity.resources.getIdentifier(TARGET_VIEW_NAME, "id", activity.packageName)
            if (resId != 0) {
                val view = activity.findViewById<View>(resId)
                if (view != null) {
                    nukeView(view)
                    if (logsEnabled) log("View nuked via Lifecycle hook.")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun executeDexKitHooks(classLoader: ClassLoader) {
        try {
            // Safe Native Load
            try {
                System.loadLibrary("dexkit")
            } catch (e: UnsatisfiedLinkError) {
                log("WARNING: System.loadLibrary failed. Assuming DexKitBridge handles it or lib is missing.")
            }

            DexKitBridge.create(classLoader, false).use { bridge ->
                val matchers = bridge.findMethod {
                    matcher {
                        paramTypes(NAV_VIEW_CLASS)
                        returnType("void")
                    }
                }

                if (matchers.isEmpty()) {
                    log("DexKit: No anchor method found.")
                    return
                }

                for (methodData in matchers) {
                    val className = methodData.className
                    // Simple filter to avoid system/base classes
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
                        param.result = null // Prevent execution
                    }
                }
            )

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
            if (logsEnabled) log("DexKit hooks applied to $className")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking class $className: $t")
        }
    }

    private fun nukeView(view: View?) {
        if (view == null) return
        try {
            if (view.visibility == View.GONE && view.layoutParams?.height == 0) return

            view.visibility = View.GONE
            view.alpha = 0f
            view.setOnClickListener(null)
            view.isClickable = false

            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                }
                view.layoutParams = params
            }
        } catch (_: Throwable) {}
    }
}