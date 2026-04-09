package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("NewApi", "DiscouragedApi", "DuplicateCreateDexKit")
class PydroidHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-PYDROID]"
        private const val NAV_VIEW_CLASS = "com.google.android.material.navigation.NavigationView"
        private const val TARGET_VIEW_NAME = "story_circle"
        private const val PREFS_CACHE = "umbra_pydroid_cache"
        private const val KEY_VERSION = "pydroid_version"
        private const val KEY_HOOK_TARGETS = "pydroid_hook_targets"
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    @Volatile private var logsEnabled = false
    private lateinit var xposedModule: XposedModule
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
    }

    private fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (t != null) xposedModule.log(Log.ERROR, TAG, message, t)
        else xposedModule.log(Log.ERROR, TAG, message)
    }

    override fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        this.xposedModule = module
        val classLoader = param.defaultClassLoader

        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)

            module.hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = chain.args[0] as Context
                    refreshConfig()
                    handleDynamicHooks(context, classLoader)
                } catch (t: Throwable) {
                    logError("Fatal Error in attach", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("Failed to hook Application.attach", t)
        }

        hookActivityLifecycle(classLoader)
    }

    private fun refreshConfig() {
        logsEnabled = PrefsManager.getHookInt(xposedModule, Constants.KEY_GLOBAL_LOGS, 0) == 1
    }

    private fun handleDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

        @Suppress("DEPRECATION")
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }

        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        val isSameVersion = (versionCode != -1 && versionCode == cachedVersion)
        val cachedTargets = cachePrefs.getStringSet(KEY_HOOK_TARGETS, null)

        if (isSameVersion && cachedTargets != null && cachedTargets.isNotEmpty()) {
            for (target in cachedTargets) {
                val parts = target.split("|")
                if (parts.size == 2) {
                    hookMainControllerClass(classLoader, parts[0], parts[1])
                }
            }
        } else {
            backgroundExecutor.submit {
                performSearchAndHookSynchronous(context, classLoader, versionCode, cachePrefs)
            }
        }
    }

    private fun performSearchAndHookSynchronous(
        context: Context,
        classLoader: ClassLoader,
        versionCode: Int,
        cachePrefs: SharedPreferences
    ) {
        val targetsToCache = mutableSetOf<String>()

        try {
            val apkPath = context.applicationInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                val matchers = bridge.findMethod {
                    matcher {
                        paramTypes(NAV_VIEW_CLASS)
                        returnType("void")
                    }
                }

                for (methodData in matchers) {
                    val className = methodData.className
                    if (!className.startsWith("com.google.") &&
                        !className.startsWith("android.") &&
                        !className.contains("Activity") &&
                        !className.contains("Fragment")) {
                        targetsToCache.add("$className|${methodData.methodName}")
                    }
                }
            }

            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putStringSet(KEY_HOOK_TARGETS, targetsToCache)
                .apply()

            for (target in targetsToCache) {
                val parts = target.split("|")
                if (parts.size == 2) {
                    hookMainControllerClass(classLoader, parts[0], parts[1])
                }
            }
        } catch (t: Throwable) {
            logError("DexKit Init Failed", t)
        }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, className: String, navMethodName: String) {
        try {
            val clazz = classLoader.loadClass(className)
            val navViewClazz = classLoader.loadClass(NAV_VIEW_CLASS)

            val navMethod = clazz.getDeclaredMethod(navMethodName, navViewClazz)
            xposedModule.hook(navMethod).intercept { return@intercept null }

            val methods = clazz.declaredMethods
            for (method in methods) {
                if (method.parameterTypes.isEmpty() &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    !Modifier.isAbstract(method.modifiers)
                ) {
                    try {
                        xposedModule.hook(method).intercept { return@intercept true }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            logError("Error hooking class $className", t)
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            val bundleClass = classLoader.loadClass("android.os.Bundle")

            val onPostCreateMethod = activityClass.getDeclaredMethod("onPostCreate", bundleClass)
            val onResumeMethod = activityClass.getDeclaredMethod("onResume")

            xposedModule.hook(onPostCreateMethod).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activity.javaClass.name.endsWith("MainActivity")) {
                    nukeStoryCircleInternal(activity)
                }
                return@intercept result
            }

            xposedModule.hook(onResumeMethod).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activity.javaClass.name.endsWith("MainActivity")) {
                    nukeStoryCircleInternal(activity)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            if (logsEnabled) logError("Failed to hook Activity Lifecycle", t)
        }
    }

    private fun nukeStoryCircleInternal(activity: Activity) {
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