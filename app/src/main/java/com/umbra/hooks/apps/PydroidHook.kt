package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("NewApi", "DiscouragedApi")
class PydroidHook : AppHook {

    companion object {
        private const val TAG = "[UMBRA-PYDROID]"
        private const val NAV_VIEW_CLASS = "com.google.android.material.navigation.NavigationView"
        private const val TARGET_VIEW_NAME = "story_circle"
        private const val PREFS_CACHE = "umbra_pydroid_cache"
        private const val KEY_VERSION = "pydroid_version"
        private const val KEY_HOOK_TARGETS = "pydroid_hook_targets"
        private const val DEXKIT_TIMEOUT_MS = 2500L
    }

    override val targetPackages = setOf("ru.iiec.pydroid3")

    @Volatile private var logsEnabled = false
    private lateinit var xposedModule: XposedModule
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {}
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
                    logsEnabled = PrefsManager.getHookInt(xposedModule, Constants.KEY_GLOBAL_LOGS, 0) == 1
                    handleDynamicHooks(context, classLoader)
                } catch (t: Throwable) { logError("Fatal Error in attach", t) }
                return@intercept result
            }
        } catch (t: Throwable) { logError("Failed Application.attach hook", t) }

        hookActivityLifecycle(classLoader)
    }

    private fun handleDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }
        val cachedTargets = cachePrefs.getStringSet(KEY_HOOK_TARGETS, null)
        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        if (isSameVersion && cachedTargets != null) {
            cachedTargets.forEach { target ->
                val parts = target.split("|")
                if (parts.size == 2) hookMainControllerClass(classLoader, parts[0], parts[1])
            }
        } else {
            val latch = CountDownLatch(1)
            backgroundExecutor.submit {
                performSearchAndHookSynchronous(context, classLoader, versionCode, cachePrefs)
                latch.countDown()
            }
            try {
                if (!latch.await(DEXKIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Umbra: Patching Pydroid Premium, please restart app.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: InterruptedException) {}
        }
    }

    private fun performSearchAndHookSynchronous(context: Context, classLoader: ClassLoader, versionCode: Int, cachePrefs: SharedPreferences) {
        val targets = mutableSetOf<String>()
        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                bridge.findMethod { matcher { paramTypes(NAV_VIEW_CLASS); returnType("void") } }.forEach { m ->
                    if (!m.className.startsWith("com.google.") && !m.className.startsWith("android.") && !m.className.contains("Activity")) {
                        targets.add("${m.className}|${m.methodName}")
                    }
                }
            }
            cachePrefs.edit().putInt(KEY_VERSION, versionCode).putStringSet(KEY_HOOK_TARGETS, targets).apply()
            targets.forEach { target ->
                val parts = target.split("|")
                if (parts.size == 2) hookMainControllerClass(classLoader, parts[0], parts[1])
            }
        } catch (t: Throwable) { logError("DexKit Failed", t) }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, className: String, navMethodName: String) {
        try {
            val clazz = classLoader.loadClass(className)
            xposedModule.hook(clazz.getDeclaredMethod(navMethodName, classLoader.loadClass(NAV_VIEW_CLASS))).intercept { null }
            clazz.declaredMethods.forEach { m ->
                if (m.parameterTypes.isEmpty() && m.returnType == Boolean::class.javaPrimitiveType && !Modifier.isAbstract(m.modifiers)) {
                    try { xposedModule.hook(m).intercept { true } } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) { logError("Error hooking $className", t) }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            val hooker = { activity: Activity -> if (activity.javaClass.name.endsWith("MainActivity")) nukeStoryCircleInternal(activity) }

            xposedModule.hook(activityClass.getDeclaredMethod("onPostCreate", classLoader.loadClass("android.os.Bundle"))).intercept { chain ->
                val r = chain.proceed(); (chain.thisObject as? Activity)?.let { hooker(it) }; r
            }
            xposedModule.hook(activityClass.getDeclaredMethod("onResume")).intercept { chain ->
                val r = chain.proceed(); (chain.thisObject as? Activity)?.let { hooker(it) }; r
            }
        } catch (t: Throwable) { logError("Lifecycle hook failed", t) }
    }

    private fun nukeStoryCircleInternal(activity: Activity) {
        val resId = activity.resources.getIdentifier(TARGET_VIEW_NAME, "id", activity.packageName)
        if (resId != 0) activity.findViewById<View>(resId)?.let { nukeView(it) }
    }

    private fun nukeView(view: View) {
        try {
            view.visibility = View.GONE
            view.alpha = 0f
            view.layoutParams?.let { p ->
                p.width = 0; p.height = 0
                if (p is ViewGroup.MarginLayoutParams) p.setMargins(0, 0, 0, 0)
                view.layoutParams = p
            }
        } catch (_: Throwable) {}
    }

    private fun logError(m: String, t: Throwable? = null) { xposedModule.log(Log.ERROR, TAG, m, t) }
}