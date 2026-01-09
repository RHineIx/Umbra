package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
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
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Ensure DexKit is loaded
        try { System.loadLibrary("dexkit") } catch (e: Throwable) {
            XposedBridge.log("$TAG Error loading DexKit: $e")
            return
        }

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as Context
                    val classLoader = context.classLoader
                    
                    XposedBridge.log("$TAG Context attached. Starting DexKit search...")
                    executeDexKitHooks(classLoader)
                    
                    // Improved: Hook UI methods earlier to prevent flickering
                    hideStoryCircleFast(classLoader)
                }
            }
        )
    }

    private fun executeDexKitHooks(classLoader: ClassLoader) {
        DexKitBridge.create(classLoader, false).use { bridge ->
            val matchers = bridge.findMethod {
                matcher {
                    paramTypes(NAV_VIEW_CLASS)
                    returnType("void")
                }
            }

            if (matchers.isEmpty()) {
                XposedBridge.log("$TAG Fatal: Could not find anchor method. Hook aborted.")
                return
            }

            XposedBridge.log("$TAG Found ${matchers.size} candidates. Filtering...")

            for (methodData in matchers) {
                val className = methodData.className
                
                // Safety Filters
                if (className.startsWith("com.google.") || className.startsWith("android.")) {
                    continue
                }
                if (className.contains("Activity") || className.contains("Fragment")) {
                    XposedBridge.log("$TAG Skipping UI Class: $className")
                    continue
                }

                hookMainControllerClass(classLoader, methodData)
            }
        }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, anchorMethod: MethodData) {
        val className = anchorMethod.className
        val navMethodName = anchorMethod.methodName 

        XposedBridge.log("$TAG >>> TARGET CONFIRMED: $className | Handler: $navMethodName")

        try {
            val clazz = XposedHelpers.findClass(className, classLoader)

            // Hook 1: Disable Ad/Menu Logic
            XposedHelpers.findAndHookMethod(
                clazz,
                navMethodName,
                NAV_VIEW_CLASS,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null 
                    }
                }
            )
            XposedBridge.log("$TAG Hooked Ad/Menu handler: $navMethodName")

            // Hook 2: Enable Premium
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
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Failed to hook ${method.name}: $e")
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Critical error hooking class $className: $t")
        }
    }

    // --- Fast UI Cleanup (Anti-Flicker) ---

    private fun hideStoryCircleFast(classLoader: ClassLoader) {
        val activityClass = "android.app.Activity"
        
        val nukeHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                nukeStoryCircleInternal(activity)
            }
        }

        try {
            // 1. Hook setContentView: Runs IMMEDIATELY after layout inflation (Before Visibility)
            // Hooking all 3 overrides of setContentView just to be safe
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", Int::class.javaPrimitiveType, nukeHook)
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", View::class.java, nukeHook)
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "setContentView", View::class.java, ViewGroup.LayoutParams::class.java, nukeHook)

            // 2. Hook onStart: Runs BEFORE onResume (Double safety)
            XposedHelpers.findAndHookMethod(activityClass, classLoader, "onStart", nukeHook)
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG Failed to setup UI hooks: $e")
        }
    }

    private fun nukeStoryCircleInternal(activity: android.app.Activity) {
        try {
            // Using getIdentifier is safe and dynamic
            val resId = activity.resources.getIdentifier("story_circle", "id", activity.packageName)
            if (resId != 0) {
                val view = activity.findViewById<View>(resId)
                nukeView(view)
            }
        } catch (_: Throwable) {}
    }

    private fun nukeView(view: View?) {
        if (view == null) return
        try {
            // Optimization: If already nuked, skip to save cycles
            if (view.visibility == View.GONE && view.layoutParams?.height == 0) return

            view.visibility = View.GONE
            view.minimumWidth = 0
            view.minimumHeight = 0
            view.alpha = 0f // Make it invisible instantly even if layout takes time

            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                    params.marginStart = 0
                    params.marginEnd = 0
                    params.topMargin = 0
                    params.bottomMargin = 0
                }
                view.layoutParams = params
            }
            
            view.setOnClickListener(null)
            view.isClickable = false
            view.isFocusable = false
        } catch (_: Throwable) {}
    }
}
