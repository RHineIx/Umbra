package com.umbra.hooks.billing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Local-only account bridge. No exported broadcasts or unrestricted queries. */
class BillingAccountProvider : ContentProvider() {
    companion object {
        const val STORE = "com.android.vending"
        const val PREFS = "billing_accounts"
        const val ENABLED = "enabled"
        const val SELECTED = "selected"
        const val ACCOUNTS = "accounts"
        const val STATUS = "status"
        const val VERSION = "version"
        const val SYNC = "sync"
        val URI: Uri = Uri.parse("content://com.umbra.hooks.billing")
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: throw IllegalStateException("Provider unavailable")
        if (method != SYNC) throw UnsupportedOperationException("Unknown operation")
        val storeUid = runCatching { ctx.packageManager.getPackageUid(STORE, 0) }.getOrNull()
        if (storeUid == null || Binder.getCallingUid() != storeUid || callingPackage != STORE) {
            throw SecurityException("Only Google Play Store may use this bridge")
        }
        val prefs = prefs(ctx)
        val names = BillingReportRules.normalizeNames(extras?.getStringArrayList(ACCOUNTS))
        // Do not allocate or apply an editor when the authenticated report
        // is unchanged. Selection is still read from preferences on every call.
        var editor: SharedPreferences.Editor? = null
        fun changes(): SharedPreferences.Editor = editor ?: prefs.edit().also { editor = it }
        if (names != null && names != prefs.getStringSet(ACCOUNTS, emptySet())) changes().putStringSet(ACCOUNTS, names)
        extras?.getString(STATUS)?.takeIf { it == "ready" || it == "unsupported" }?.let {
            if (it != prefs.getString(STATUS, null)) changes().putString(STATUS, it)
        }
        extras?.getString(VERSION)?.take(100)?.let {
            if (it != prefs.getString(VERSION, null)) changes().putString(VERSION, it)
        }
        editor?.apply()
        val selected = prefs.getString(SELECTED, null)
        return Bundle().apply {
            // An account must be present in the current authenticated account report.
            if (prefs.getBoolean(ENABLED, false) && selected != null && names?.contains(selected) == true) {
                putString(SELECTED, selected)
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw SecurityException("Queries disabled")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Writes disabled")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Writes disabled")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Writes disabled")
}
