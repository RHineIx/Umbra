package com.umbra.hooks.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ResultReceiver
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicit IPC with OS-verified caller identity on Android 14+. */
class UpdateRequestReceiver : BroadcastReceiver() {
    companion object {
        internal const val ACTION = "com.umbra.hooks.UPDATE_REQUEST"
        private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(4)).apply { allowCoreThreadTimeOut(true) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 34 || intent.action != ACTION) return
        val caller = sentFromPackage ?: return
        if (caller !in UpdateProvider.TARGETS ||
            runCatching { context.packageManager.getPackageUid(caller, 0) }.getOrNull() != sentFromUid) {
            Log.w("UMBRA-UPDATE", "Rejected update request sender")
            return
        }
        val method = intent.getStringExtra("method") ?: return
        if (method !in setOf("claim", "shown", "release", "download")) return
        val arg = intent.getStringExtra("token")
        if (arg != null && arg.length > 64) return
        val reply = intent.getParcelableExtra("reply", ResultReceiver::class.java) ?: return
        val pending = goAsync()
        try {
            worker.execute {
                try {
                    val result = runCatching { UpdateClaims.call(context.applicationContext, caller, method, arg) }
                        .onFailure { Log.e("UMBRA-UPDATE", "Central request failed", it) }
                        .getOrElse { UpdateClaims.status("central_error") }
                    reply.send(0, result)
                } finally {
                    pending.finish()
                }
            }
        } catch (error: Throwable) {
            try { reply.send(0, UpdateClaims.status("central_busy")) }
            finally { pending.finish() }
        }
    }
}
