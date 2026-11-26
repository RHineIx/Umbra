package com.umbra.hooks.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** The provider runs in Umbra, not in the requesting application's process. */
class UpdateProvider : ContentProvider() {
    companion object {
        internal val URI = Uri.parse("content://com.umbra.hooks.updates")
        // No keyboard or checkout interruptions during updates.
        internal val TARGETS = setOf("ru.iiec.pydroid3", "org.picquantmedia.grafika", "com.michaldrabik.showly2")
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val caller = callingPackage ?: throw SecurityException("Missing caller")
        if (caller !in TARGETS || ctx.packageManager.getPackageUid(caller, 0) != Binder.getCallingUid())
            throw SecurityException("Unsupported update client")
        val identity = Binder.clearCallingIdentity()
        try {
            return UpdateClaims.call(ctx, caller, method, arg)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw SecurityException("Queries disabled")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Writes disabled")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Writes disabled")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Writes disabled")
}
