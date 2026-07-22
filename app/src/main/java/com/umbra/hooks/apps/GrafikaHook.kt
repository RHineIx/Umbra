package com.umbra.hooks.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import com.google.android.material.tabs.TabLayout
import com.umbra.hooks.core.AppHook
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.*

@SuppressLint("NewApi")
class GrafikaHook : AppHook {

    companion object {
        private const val TAG = "UMBRA-GRAFIKA"
        private const val PREFS_CACHE = "umbra_grafika_cache"
        private const val KEY_VERSION = "grafika_version"
        private const val KEY_PREMIUM_TARGETS = "grafika_premium_targets"
        private const val KEY_PAIRIP_TARGET = "grafika_pairip_target"
    }

    override val targetPackages = setOf("org.picquantmedia.grafika")

    @Volatile private var logsEnabled = false
    @Volatile private var isUiTweaked = false
    private lateinit var xposedModule: XposedModule

    private val backgroundExecutor: ExecutorService = ThreadPoolExecutor(
        0, 1,
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()
    )

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
                    logInfo("[INFO] Grafika Application.attach intercepted. Initializing Optimized Dynamic Hooks...")

                    handleDynamicHooks(context, classLoader)
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

    private fun handleDynamicHooks(context: Context, classLoader: ClassLoader) {
        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).versionCode } catch (_: Exception) { -1 }

        val cachedPremiumTargets = cachePrefs.getStringSet(KEY_PREMIUM_TARGETS, null)
        val cachedPairipTarget = cachePrefs.getString(KEY_PAIRIP_TARGET, null)
        val isSameVersion = (versionCode != -1 && versionCode == cachePrefs.getInt(KEY_VERSION, -1))

        if (isSameVersion && cachedPremiumTargets != null) {
            logInfo("[CACHE] Valid version cache ($versionCode) found. Applying optimized offsets.")
            applyHooks(classLoader, cachedPremiumTargets, cachedPairipTarget)
        } else {
            logInfo("[UPDATE] App version updated or cache missing. Initiating DexKit background search...")
            backgroundExecutor.submit {
                try {
                    performSearchAndHookSynchronous(context, classLoader, versionCode, cachePrefs)
                    logInfo("[SUCCESS] Background DexKit analysis finished.")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(context, "Umbra: Grafika optimized & patched. Please restart.", Toast.LENGTH_LONG).show()
                        } catch (e: Throwable) {
                            logError("[WARN] Context invalid for Toast display", e)
                        }
                    }
                } catch (t: Throwable) {
                    logError("[ERROR] Background DexKit analysis failed", t)
                } finally {
                    backgroundExecutor.shutdown()
                }
            }
        }
    }

    private fun loadDexKitSafely() {
        try {
            System.loadLibrary("dexkit")
        } catch (e: UnsatisfiedLinkError) {
            logInfo("[WARN] Native load failed. Invoking absolute path extraction.")
            try {
                val appInfoMethod = xposedModule.javaClass.methods.firstOrNull {
                    it.name == "getApplicationInfo" || it.name == "getModuleApplicationInfo"
                }
                val appInfo = appInfoMethod?.invoke(xposedModule) as? android.content.pm.ApplicationInfo

                if (appInfo != null) {
                    val libPath = "${appInfo.nativeLibraryDir}/libdexkit.so"
                    System.load(libPath)
                    logInfo("[SUCCESS] libdexkit.so injected from absolute path.")
                } else {
                    throw UnsatisfiedLinkError("Could not resolve module ApplicationInfo")
                }
            } catch (e2: Throwable) {
                logError("[FATAL] Complete failure loading DexKit.", e2)
                throw e2
            }
        }
    }

    private fun performSearchAndHookSynchronous(context: Context, classLoader: ClassLoader, versionCode: Int, cachePrefs: SharedPreferences) {
        try {
            loadDexKitSafely()
        } catch (e: Throwable) {
            return
        }

        val premiumTargets = mutableSetOf<String>()
        var pairipTarget: String? = null

        try {
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->

                bridge.findMethod {
                    matcher {
                        name("nativeIsProActive")
                        returnType("boolean")
                    }
                }.firstOrNull()?.let { m ->
                    premiumTargets.add(m.className)
                    logInfo("[SEARCH] Found NativePremiumAccessManager: ${m.className}")
                }

                bridge.findClass {
                    matcher {
                        usingStrings("ITEM_ALREADY_OWNED", "ITEM_NOT_OWNED")
                    }
                }.firstOrNull()?.let { c ->
                    premiumTargets.add(c.name)
                    logInfo("[SEARCH] Found BillingHelper: ${c.name}")
                }

                bridge.findClass {
                    matcher {
                        usingStrings("PAYWALL_INTENT")
                    }
                }.firstOrNull()?.let { c ->
                    pairipTarget = c.name
                    logInfo("[SEARCH] Found Pairip LicenseClient: ${c.name}")
                }
            }

            cachePrefs.edit()
                .putInt(KEY_VERSION, versionCode)
                .putStringSet(KEY_PREMIUM_TARGETS, premiumTargets)
                .putString(KEY_PAIRIP_TARGET, pairipTarget)
                .apply()

            applyHooks(classLoader, premiumTargets, pairipTarget)

        } catch (t: Throwable) {
            logError("[ERROR] DexKit parsing engine encountered an exception", t)
        }
    }

    private fun applyHooks(classLoader: ClassLoader, premiumClasses: Set<String>, pairipClass: String?) {
        var hookedPremiumMethods = 0
        for (className in premiumClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { m ->
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                        try {
                            xposedModule.hook(m).intercept { true }
                            hookedPremiumMethods++
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                logError("[WARN] Failed to hook premium class: $className", e)
            }
        }
        logInfo("[SUCCESS] Aggressive Premium state forced. Hooked $hookedPremiumMethods methods.")

        val targetPairip = pairipClass ?: "com.pairip.licensecheck.LicenseClient"
        try {
            val clazz = classLoader.loadClass(targetPairip)
            clazz.declaredMethods.forEach { m ->
                if (m.name.contains("checkLicense", ignoreCase = true) ||
                    m.name.contains("Paywall", ignoreCase = true) ||
                    m.name.contains("ErrorDialog", ignoreCase = true)) {
                    xposedModule.hook(m).intercept { null }
                }
            }
            logInfo("[SUCCESS] Pairip integrity checks neutralized.")
        } catch (e: Exception) {
            logInfo("[WARN] Pairip LicenseClient not found. Assuming removed or fully obfuscated.")
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            xposedModule.hook(activityClass.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity

                if (!isUiTweaked && activity != null && activity.javaClass.name.endsWith("StartActivity")) {
                    val rootView = activity.window.decorView.rootView

                    val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            try {
                                if (!isUiTweaked) {
                                    applyFunctionalUITweaks(activity)
                                }
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
            logInfo("[SUCCESS] Activity lifecycle observation active with micro-optimization.")
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
                    isUiTweaked = true
                    logInfo("[UI] Connect with us section and social buttons neutralized permanently.")
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