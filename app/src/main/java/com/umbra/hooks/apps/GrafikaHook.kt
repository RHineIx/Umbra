package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.tabs.TabLayout
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@SuppressLint("NewApi")
class GrafikaHook : AppHook {

    companion object {
        private const val TAG = "UMBRA-GRAFIKA"
    }

    override val targetPackages = setOf("org.picquantmedia.grafika")
    @Volatile private var logsEnabled = false
    private lateinit var xposedModule: XposedModule

    private fun logInfo(message: String) {
        if (logsEnabled) xposedModule.log(Log.INFO, TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (logsEnabled) {
            if (t != null) xposedModule.log(Log.ERROR, TAG, message, t)
            else xposedModule.log(Log.ERROR, TAG, message)
        }
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
                    logInfo("[INFO] Grafika Application.attach intercepted. Injecting aggressive hooks...")

                    bypassPairipLicense(classLoader)
                    hijackBillingAndPremiumState(classLoader)
                } catch (t: Throwable) {
                    logError("[FATAL] Error occurred inside attach execution block", t)
                }
                return@intercept result
            }
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hook Application.attach", t)
        }

        hookActivityLifecycle(classLoader)
    }

    private fun bypassPairipLicense(classLoader: ClassLoader) {
        try {
            val pairipClass = classLoader.loadClass("com.pairip.licensecheck.LicenseClient")
            pairipClass.declaredMethods.forEach { m ->
                if (m.name == "checkLicense" || m.name == "startPaywallActivity" || m.name == "startErrorDialogActivity") {
                    xposedModule.hook(m).intercept { null }
                }
            }
            logInfo("[SUCCESS] Pairip integrity checks neutralized.")
        } catch (e: ClassNotFoundException) {
            logInfo("[WARN] Pairip LicenseClient not found. Assuming removed or obfuscated.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to bypass Pairip", t)
        }
    }

    private fun hijackBillingAndPremiumState(classLoader: ClassLoader) {
        try {
            val billingHelperClass = classLoader.loadClass("com.grafika.billing.BillingHelper")
            val targetClassesToHook = mutableSetOf<Class<*>>()

            // Extract the obfuscated Lv11 class (from field 'c')
            val premiumClassC = billingHelperClass.declaredFields.firstOrNull { it.name == "c" }?.type
            if (premiumClassC != null) targetClassesToHook.add(premiumClassC)

            // Extract the obfuscated Ltt4 class (from field 'b')
            val premiumClassB = billingHelperClass.declaredFields.firstOrNull { it.name == "b" }?.type
            if (premiumClassB != null) targetClassesToHook.add(premiumClassB)

            // Add the native manager explicitly
            try {
                val nativeManagerClass = classLoader.loadClass("com.grafika.billing.NativePremiumAccessManager")
                targetClassesToHook.add(nativeManagerClass)
            } catch (e: Exception) {
                logError("[WARN] NativePremiumAccessManager not found", e)
            }

            var hookedMethods = 0
            for (clazz in targetClassesToHook) {
                clazz.declaredMethods.forEach { m ->
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                        try {
                            xposedModule.hook(m).intercept { true }
                            hookedMethods++
                        } catch (_: Exception) {}
                    }
                }
            }
            logInfo("[SUCCESS] Aggressive Premium state forced. Hooked $hookedMethods boolean methods across target classes.")

            billingHelperClass.declaredMethods.forEach { m ->
                if (m.name == "h" && m.parameterTypes.size == 2 && m.parameterTypes[1] == String::class.java) {
                    xposedModule.hook(m).intercept { 
                        logInfo("[SUCCESS] Silently blocked Paywall Activity launch.")
                        null 
                    }
                }
            }
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hijack BillingHelper and Premium state", t)
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            xposedModule.hook(activityClass.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity

                if (activity != null && activity.javaClass.name.endsWith("StartActivity")) {
                    val rootView = activity.window.decorView.rootView
                    
                    val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            try {
                                applyFunctionalUITweaks(activity)
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
            logInfo("[SUCCESS] Activity lifecycle observation active for UI tweaks.")
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
                    logInfo("[UI] Connect with us section and social buttons neutralized.")
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