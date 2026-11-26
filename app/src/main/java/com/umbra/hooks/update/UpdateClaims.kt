package com.umbra.hooks.update

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.UUID

/** One claim coordinator shared by both transports in Umbra's process. */
internal object UpdateClaims {
    private data class Lease(val tag: String, val token: String, val caller: String, val expires: Long)
    private var lease: Lease? = null
    private var downloadGrant: Lease? = null
    fun status(value: String) = Bundle().apply { putString("status", value) }

    @Synchronized fun call(ctx: Context, caller: String, method: String, arg: String?): Bundle {
        return when (method) {
            "claim" -> {
                val result = UpdateRepository.check(ctx) ?: return status("check_failed_or_throttled")
                val update = result.update ?: return status("up_to_date")
                if (UpdateRepository.wasShown(ctx, update.tag)) return status("already_shown")
                val now = SystemClock.elapsedRealtime()
                if (lease?.let { it.expires > now } == true) return status("claimed_by_another_activity")
                val token = UUID.randomUUID().toString()
                lease = Lease(update.tag, token, caller, now + 30_000L)
                downloadGrant = Lease(update.tag, token, caller, now + 30 * 60_000L)
                Bundle().apply {
                    putString("status", "available")
                    putString("token", token)
                    putString("tag", update.tag)
                    putString("installed", UpdateRepository.installedVersion(ctx))
                }
            }
            "shown", "release" -> {
                val current = lease
                if (current == null || current.caller != caller || current.token != arg) return status("invalid_claim")
                if (method == "shown" && !UpdateRepository.markShown(ctx, current.tag)) return status("persistence_failed")
                lease = null
                if (method == "release" && downloadGrant?.token == arg) downloadGrant = null
                status("acknowledged")
            }
            "download" -> {
                val grant = downloadGrant
                if (grant == null || grant.caller != caller || grant.token != arg ||
                    grant.expires <= SystemClock.elapsedRealtime()) return status("invalid_claim")
                val update = UpdateRepository.check(ctx)?.update
                if (update == null || update.tag != grant.tag) return status("release_unavailable")
                status(UpdateDownload.enqueue(ctx, update))
            }
            else -> status("unknown_operation")
        }
    }
}
