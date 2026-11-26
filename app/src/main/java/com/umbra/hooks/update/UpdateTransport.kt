package com.umbra.hooks.update

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs on the injected worker; no app UI or network work here. */
internal object UpdateTransport {
    fun call(context: Context, method: String, token: String?): Bundle? {
        if (Build.VERSION.SDK_INT < 34) {
            return context.contentResolver.call(UpdateProvider.URI, method, token, null)
        }
        val response = AtomicReference<Bundle?>()
        val complete = CountDownLatch(1)
        val callback = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                response.set(resultData)
                complete.countDown()
            }
        }
        val intent = Intent(UpdateRequestReceiver.ACTION)
            .setComponent(ComponentName("com.umbra.hooks", "com.umbra.hooks.update.UpdateRequestReceiver"))
            .putExtra("method", method)
            .putExtra("token", token)
            .putExtra("reply", callback)
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        if (!complete.await(20, TimeUnit.SECONDS)) return Bundle().apply { putString("status", "broadcast_timeout") }
        return response.get()
    }
}
