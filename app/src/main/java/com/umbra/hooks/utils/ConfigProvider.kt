package com.umbra.hooks.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder

/**
 * Optimized & Secured Provider
 * Handles Integers Only.
 * Security: Allows access ONLY from Gboard and Umbra itself.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        // Allowed packages (White-list)
        private val ALLOWED_PACKAGES = setOf(
            "com.google.android.inputmethod.latin", // Gboard
            "com.umbra.hooks"                       // Self
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        
        // --- SECURITY CHECK START ---
        if (!isCallerAllowed(context)) {
            // Reject unauthorized access quietly
            return null
        }
        // --- SECURITY CHECK END ---

        // URI Format: content://com.umbra.hooks.provider/int/<key>
        val pathSegments = uri.pathSegments
        if (pathSegments.size < 2 || pathSegments[0] != "int") return null

        val key = pathSegments[1]
        val prefs = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE)
        
        val cursor = MatrixCursor(arrayOf("value"))
        try {
            val value = prefs.getInt(key, 0)
            cursor.addRow(arrayOf(value))
        } catch (_: Exception) {
            return null
        }
        
        return cursor
    }

    /**
     * Verifies if the calling process belongs to an allowed package.
     */
    private fun isCallerAllowed(context: Context): Boolean {
        try {
            val callingUid = Binder.getCallingUid()
            val myUid = android.os.Process.myUid()

            // Always allow self
            if (callingUid == myUid) return true

            val pm = context.packageManager
            val packages = pm.getPackagesForUid(callingUid) ?: return false

            for (pkg in packages) {
                if (pkg in ALLOWED_PACKAGES) {
                    return true
                }
            }
        } catch (_: Throwable) {
            return false // Fail safe
        }
        return false
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
