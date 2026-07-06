package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.button.MaterialButton
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
                    logInfo("[INFO] Grafika Application.attach intercepted. Injecting hooks...")

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
            logInfo("[WARN] Pairip LicenseClient not found. It might have been removed or heavily obfuscated.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to bypass Pairip", t)
        }
    }

    private fun hijackBillingAndPremiumState(classLoader: ClassLoader) {
        try {
            val billingHelperClass = classLoader.loadClass("com.grafika.billing.BillingHelper")

            // 1. Force Premium State dynamically by extracting the obfuscated Lv11 class
            val premiumClass = billingHelperClass.declaredFields.firstOrNull { it.name == "c" }?.type
            if (premiumClass != null) {
                var hookedMethods = 0
                premiumClass.declaredMethods.forEach { m ->
                    // Hook all boolean methods taking 0 parameters (e.g., m(), n() which represent premium status)
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                        xposedModule.hook(m).intercept { true }
                        hookedMethods++
                    }
                }
                logInfo("[SUCCESS] Premium state forced. Hooked $hookedMethods boolean methods in ${premiumClass.name}.")
            } else {
                logError("[WARN] Could not find premium state field 'c' in BillingHelper.")
            }

            // 2. Redirect Pro Button to Telegram
            billingHelperClass.declaredMethods.forEach { m ->
                if (m.name == "h" && m.parameterTypes.size == 2 && m.parameterTypes[1] == String::class.java) {
                    xposedModule.hook(m).intercept { chain ->
                        val context = chain.args[0] as? Context
                        if (context != null) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineIx"))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                logInfo("[SUCCESS] Redirected Pro button click to Telegram.")
                            } catch (e: Exception) {
                                logError("[ERROR] Failed to start Telegram Intent", e)
                            }
                        }
                        null // Cancel the original billing activity launch
                    }
                }
            }
        } catch (t: Throwable) {
            logError("[ERROR] Failed to hijack BillingHelper", t)
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
                                applyUITweaks(activity)
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
            logInfo("[SUCCESS] Activity lifecycle observation active for StartActivity UI tweaks.")
        } catch (t: Throwable) {
            logError("[ERROR] Failed to attach to Activity lifecycle", t)
        }
    }

    private fun applyUITweaks(activity: Activity) {
        // 1. Hide "Connect with us" label securely via dynamic ID
        val connectId = activity.resources.getIdentifier("label_connect_with_us", "id", activity.packageName)
        if (connectId != 0) {
            activity.findViewById<View>(connectId)?.let { view ->
                view.visibility = View.GONE
                // Hide its parent if it is a HorizontalScrollView
                val parent = view.parent as? ViewGroup
                if (parent != null && parent.javaClass.name.contains("HorizontalScrollView")) {
                    parent.visibility = View.GONE
                }
            }
        }

        // 2. Modify Pro Button dynamically via Reflection
        try {
            val buttonFields = activity.javaClass.declaredFields.filter { 
                it.type.name == "com.google.android.material.button.MaterialButton" 
            }
            for (field in buttonFields) {
                field.isAccessible = true
                val btn = field.get(activity) as? MaterialButton
                // Assuming the Pro button contains an icon (crown)
                if (btn != null && btn.icon != null) {
                    btn.text = "Umbra"
                    btn.icon = null
                }
            }
        } catch (e: Throwable) {
            logError("[WARN] Failed to modify Pro button via reflection", e)
        }

        // 3. Remove Account Tab securely via Reflection
        try {
            val tabFields = activity.javaClass.declaredFields.filter { 
                it.type.name == "com.google.android.material.tabs.TabLayout" 
            }
            for (field in tabFields) {
                field.isAccessible = true
                val tabLayout = field.get(activity) as? TabLayout
                if (tabLayout != null && tabLayout.tabCount >= 3) {
                    tabLayout.removeTabAt(2)
                    logInfo("[UI] Account Tab neutralized.")
                }
            }
        } catch (e: Throwable) {
            logError("[WARN] Failed to neutralize Account Tab", e)
        }
    }
}
