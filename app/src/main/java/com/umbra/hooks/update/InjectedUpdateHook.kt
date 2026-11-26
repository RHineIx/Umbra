package com.umbra.hooks.update

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Framework lifecycle only. All HTTP and shared notification state live in Umbra. */
internal class InjectedUpdateHook(private val module: XposedModule, private val target: String) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var detached = false
    private var resumed = WeakReference<Activity>(null)
    private var dialog: Dialog? = null
    private var nextAttempt = 0L
    private var pending = false
    private var generation = 0L

    private fun report(message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) module.log(Log.ERROR, "UMBRA-UPDATE", "[$target] $message", error)
            else if (com.umbra.hooks.utils.PrefsManager.getHookInt(module,
                    com.umbra.hooks.utils.Constants.KEY_GLOBAL_LOGS, 0) == 1)
                module.log(Log.INFO, "UMBRA-UPDATE", "[$target] $message")
        }
    }

    fun install() {
        try {
            handles += module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val activity = chain.args.firstOrNull() as? Activity
                    if (activity != null && activity.packageName == target && !detached) {
                        resume(activity)
                    }
                }.onFailure { report("Resume callback failed", it) }
                result
            }
            handles += module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnPause", Activity::class.java)).intercept { chain ->
                runCatching {
                    if (chain.args.firstOrNull() === resumed.get()) {
                        generation++
                        resumed.clear()
                        dialog?.dismiss()
                        dialog = null
                    }
                }.onFailure { report("Pause cleanup failed", it) }
                chain.proceed()
            }
            report("Lifecycle hooks installed")
        } catch (error: Throwable) {
            detach()
            throw error
        }
    }

    private fun resume(activity: Activity) {
        resumed = WeakReference(activity)
        val ticket = ++generation
        if (dialog?.isShowing == true) return
        report("Activity resumed: ${activity.javaClass.simpleName}")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        val reference = WeakReference(activity)
        fun awaitWindow() {
            val current = reference.get() ?: return
            if (detached || ticket != generation || current !== resumed.get() ||
                current.isFinishing || current.isDestroyed) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                report("Window readiness timed out or another check is pending")
                return
            }
            if (pending || !current.hasWindowFocus()) {
                main.postDelayed({ awaitWindow() }, 350L)
                return
            }
            if (SystemClock.elapsedRealtime() < nextAttempt) {
                report("Check throttled")
                return
            }
            report("Window ready; requesting central update")
            request(current)
        }
        main.postDelayed({ awaitWindow() }, 700L)
    }

    private fun request(activity: Activity) {
        pending = true
        nextAttempt = SystemClock.elapsedRealtime() + 60_000L
        val context = activity.applicationContext
        val reference = WeakReference(activity)
        runCatching {
            worker.execute {
                val response = runCatching {
                    report("IPC request started: " + if (android.os.Build.VERSION.SDK_INT >= 34) "explicit broadcast" else "legacy provider")
                    UpdateTransport.call(context, "claim", null)
                }.onFailure { report("Central IPC request failed", it) }.getOrNull()
                report("IPC result: ${response?.getString("status") ?: "unavailable"}")
                val token = response?.getString("token")
                val installed = response?.getString("installed").orEmpty()
                val update = response?.getString("tag")?.let {
                    UpdateRules.available(installed, it, false, false)
                }
                main.post {
                    val deadline = SystemClock.elapsedRealtime() + 12_000L
                    fun showWhenReady() {
                        val current = reference.get()
                        if (!detached && token != null && update != null && current != null &&
                            current === resumed.get() && !current.isFinishing && !current.isDestroyed &&
                            !current.hasWindowFocus() && SystemClock.elapsedRealtime() < deadline) {
                            main.postDelayed({ showWhenReady() }, 350L)
                            return
                        }
                        pending = false
                        var shown = false
                        if (!detached && token != null && update != null && current != null &&
                            current === resumed.get() && !current.isFinishing && !current.isDestroyed &&
                            current.hasWindowFocus()) {
                            runCatching {
                                dialog = UpdateDialog.show(current, installed, update) {
                                    download(current, context, token, update)
                                }
                                shown = dialog?.isShowing == true
                                report("Dialog shown: $shown, version=${update.tag}")
                            }.onFailure { report("Dialog creation failed", it) }
                        }
                        if (token != null) {
                            if (!shown) {
                                nextAttempt = 0L
                                report("Dialog not shown; releasing claim")
                            }
                            finishClaim(context, token, shown)
                            if (!shown && !detached && resumed.get() !== current) resumed.get()?.let { resume(it) }
                        }
                    }
                    showWhenReady()
                }
            }
        }.onFailure { pending = false; report("Worker rejected request", it) }
    }

    private fun download(activity: Activity, context: Context, token: String, update: AppUpdate) {
        val reference = WeakReference(activity)
        fun fallback() {
            val current = reference.get() ?: return
            if (!detached && current === resumed.get() && !current.isFinishing && !current.isDestroyed) {
                UpdateDialog.openRelease(current, update)
            }
        }
        runCatching {
            worker.execute {
                val result = runCatching { UpdateTransport.call(context, "download", token) }
                    .onFailure { report("Download request failed", it) }.getOrNull()?.getString("status")
                if (result != "download_started" && result != "download_exists") main.post { fallback() }
            }
        }.onFailure { fallback() }
    }

    private fun finishClaim(context: Context, token: String, shown: Boolean) {
        // Allow the provider's short lease to expire if teardown has shut down
        // the worker before an acknowledgement could be sent.
        runCatching {
            worker.execute {
                runCatching { UpdateTransport.call(context, if (shown) "shown" else "release", token) }
                    .onSuccess { report("ACK " + (it?.getString("status") ?: "unavailable")) }
                    .onFailure { report("Claim acknowledgement failed", it) }
            }
        }
    }

    fun detach() {
        detached = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
        worker.shutdownNow()
        main.post {
            resumed.clear()
            dialog?.dismiss()
            dialog = null
        }
    }
}
