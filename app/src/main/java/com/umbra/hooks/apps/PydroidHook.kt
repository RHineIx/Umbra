package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.umbra.hooks.core.BaseDynamicHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@SuppressLint("NewApi", "DiscouragedApi")
class PydroidHook : BaseDynamicHook() {

    override val targetPackages = setOf("ru.iiec.pydroid3")
    override val tag = "UMBRA-PYDROID"
    override val prefsCacheName = "umbra_pydroid_cache"
    override val successToastMessage = "Umbra: Pydroid Premium patched. Please restart."

    companion object {
        private const val NAV_VIEW_CLASS = "com.google.android.material.navigation.NavigationView"
        private const val TARGET_VIEW_NAME = "story_circle"
        private const val KEY_HOOK_TARGETS = "pydroid_hook_targets"
    }

    override fun hasValidCache(cachePrefs: SharedPreferences): Boolean {
        return cachePrefs.contains(KEY_HOOK_TARGETS)
    }

    override fun performSearchSynchronous(bridge: DexKitBridge, cachePrefs: SharedPreferences) {
        val targets = mutableSetOf<String>()
        
        bridge.findMethod { 
            matcher { paramTypes(NAV_VIEW_CLASS); returnType("void") } 
        }.forEach { m ->
            if (!m.className.startsWith("com.google.") && !m.className.startsWith("android.") && !m.className.contains("Activity")) {
                targets.add("${m.className}|${m.methodName}")
            }
        }
        
        logInfo("[SEARCH] Extracted ${targets.size} controller vectors.")
        cachePrefs.edit().putStringSet(KEY_HOOK_TARGETS, targets).apply()
        
        applyHooks(targetClassLoader, cachePrefs)
    }

    override fun applyHooks(classLoader: ClassLoader, cachePrefs: SharedPreferences) {
        val cachedTargets = cachePrefs.getStringSet(KEY_HOOK_TARGETS, emptySet()) ?: emptySet()
        
        cachedTargets.forEach { target ->
            val parts = target.split("|")
            if (parts.size == 2) {
                hookMainControllerClass(classLoader, parts[0], parts[1])
            }
        }
    }

    private fun hookMainControllerClass(classLoader: ClassLoader, className: String, navMethodName: String) {
        try {
            val clazz = classLoader.loadClass(className)
            val hookHandle = xposedModule.hook(clazz.getDeclaredMethod(navMethodName, classLoader.loadClass(NAV_VIEW_CLASS))).intercept { null }
            registerHook(hookHandle)
            
            var boolMethodHooks = 0
            clazz.declaredMethods.forEach { m ->
                if (m.parameterTypes.isEmpty() && m.returnType == Boolean::class.javaPrimitiveType && !Modifier.isAbstract(m.modifiers)) {
                    try { 
                        val boolHandle = xposedModule.hook(m).intercept { true } 
                        registerHook(boolHandle)
                        boolMethodHooks++
                    } catch (_: Throwable) {}
                }
            }
            logInfo("[HOOK] Controller $className neutralized. Premium state forced on $boolMethodHooks flags.")
        } catch (t: Throwable) { 
            logError("[ERROR] Hook implementation failed for class $className", t) 
        }
    }

    override fun onPackageLoaded(classLoader: ClassLoader) {
        hookActivityLifecycle(classLoader)
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")

            val hookHandle = xposedModule.hook(activityClass.getDeclaredMethod("onResume")).intercept { chain ->
                val r = chain.proceed()
                val activity = chain.thisObject as? Activity
                
                if (activity != null && activity.javaClass.name.endsWith("MainActivity")) {
                    val rootView = activity.window.decorView.rootView
                    val resId = activity.resources.getIdentifier(TARGET_VIEW_NAME, "id", activity.packageName)
                    
                    if (resId != 0) {
                        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                val targetView = activity.findViewById<View>(resId)
                                if (targetView != null && targetView.visibility != View.GONE) {
                                    nukeView(targetView)
                                    logInfo("[UI] Target view '$TARGET_VIEW_NAME' neutralized.")
                                    
                                    if (rootView.viewTreeObserver.isAlive) {
                                        rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    }
                                }
                            }
                        }
                        
                        rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                    }
                }
                r
            }
            registerHook(hookHandle)
            logInfo("[SUCCESS] Activity lifecycle observation active.")
        } catch (t: Throwable) { 
            logError("[ERROR] Failed to attach to Activity lifecycle", t) 
        }
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
        } catch (e: Throwable) {
            logError("[WARN] Exception during UI element neutralization", e)
        }
    }
}
