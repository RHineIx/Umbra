package com.umbra.hooks.apps

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import java.lang.reflect.Modifier

class PydroidHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-PYDROID]"
        private const val NAV_VIEW_CLASS = "com.google.android.material.navigation.NavigationView"
        private const val TARGET_VIEW_NAME = "story_circle"
        private const val PREFS_CACHE = "umbra_pydroid_cache"
        private const val KEY_VERSION = "pydroid_version"
        private const val KEY_HOOK_TARGETS = "pydroid_hook_targets" // Stores Set of "ClassName|MethodName"
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    @Volatile private var logsEnabled = false

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
    }

    private fun log(message: String) {
        if (logsEnabled) {
            XposedBridge.log("$TAG $message")
        }
    }

    override fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook Application.attach to initialize configs and Dynamic Hooks
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

                        // 1. Heavy DexKit operations handled via Cache or Synchronous Execution (to prevent UI crashes)
                        handleDynamicHooks(context, lpparam.classLoader)

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

    private fun handleDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        
        @Suppress("DEPRECATION")
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        
        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode != -1 && versionCode == cachedVersion)
        
        val cachedTargets = cachePrefs.getStringSet(KEY_HOOK_TARGETS, null)

        if (isSameVersion && cachedTargets != null && cachedTargets.isNotEmpty()) {
            // Cache Hit: Apply hooks synchronously from stored set
            log("Cache hit (Version: $versionCode). Applying hooks to ${cachedTargets.size} targets.")
            
            for (target in cachedTargets) {
                val parts = target.split("|")
                if (parts.size == 2) {
                    hookMainControllerClass(classLoader, parts[0], parts[1])
                }
            }
        } else {
            // Cache Miss: Execute Synchronously to prevent Race Conditions and NPEs during UI inflation
            log("Cache miss (Current: $versionCode, Cached: $cachedVersion). Starting synchronous DexKit search. Expect short freeze...")
            performSearchAndHookSynchronous(classLoader, versionCode, cachePrefs)
        }
    }

    private fun performSearchAndHookSynchronous(
        classLoader: ClassLoader,
        versionCode: Int,
        cachePrefs: SharedPreferences
    ) {
        val targetsToCache = mutableSetOf<String>()

        try {
            DexKitBridge.create(classLoader, true).use { bridge ->
                val matchers = bridge.findMethod {
                    matcher {
                        paramTypes(NAV_VIEW_CLASS)
                        returnType("void")
                    }
                }

                for (methodData in matchers) {
                    val className = methodData.className
                    // Simple filter to avoid system/base classes
                    if (!className.startsWith("com.google.") && 
                        !className.startsWith("android.") && 
                        !className.contains("Activity") && 
                        !className.contains("Fragment")) {
                        
                        targetsToCache.add("$className|${methodData.methodName}")
                    }
                }
            }

            // Save to Cache (Always save, even if empty, to prevent infinite loops)
            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putStringSet(KEY_HOOK_TARGETS, targetsToCache)
                .apply()

            log("Synchronous search completed. Found ${targetsToCache.size} targets. Cached for version $versionCode.")

            // Apply Hooks
            for (target in targetsToCache) {
                val parts = target.split("|")
                if (parts.size == 2) {
                    hookMainControllerClass(classLoader, parts[0], parts[1])
                }
            }

        } catch (t: Throwable) {
            XposedBridge.log("$TAG DexKit Init Failed: $t")
        }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, className: String, navMethodName: String) {
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
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    param.result = true
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            if (logsEnabled) log("Hooks successfully applied to $className")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking class $className: $t")
        }
    }

    /**
     * Optimized UI Hook:
     * Hooks 'onPostCreate' and 'onResume' of Activity, but strictly limits execution 
     * to the MainActivity to guarantee zero impact on performance/battery during regular app usage.
     */
    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            
            val lifecycleHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    
                    // Precise Targeting: Execute ONLY if this is the MainActivity
                    if (activity.javaClass.name.endsWith("MainActivity")) {
                        nukeStoryCircleInternal(activity)
                    }
                }
            }

            XposedBridge.hookAllMethods(activityClass, "onPostCreate", lifecycleHook)
            XposedBridge.hookAllMethods(activityClass, "onResume", lifecycleHook)

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
                }
            }
        } catch (_: Throwable) {}
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