package com.umbra.hooks.utils

import android.content.Context
import android.net.Uri

object PrefsManager {
    
    private const val AUTHORITY = "com.umbra.hooks.provider"

    // --- Local Write (Used by GboardActivity) ---
    // No need for fixPermissions anymore because we use a ContentProvider
    fun putInt(context: Context, key: String, value: Int) {
        val prefs = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).apply() // 'apply' is faster than 'commit'
    }

    fun getLocalInt(context: Context, key: String, def: Int): Int {
        return context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE)
            .getInt(key, def)
    }

    // --- Remote Read (Used by GboardHook via IPC) ---
    // Only Int support is needed for Gboard (Limit & Retention)
    fun getRemoteInt(context: Context, key: String, defValue: Int): Int {
        try {
            // URI Format: content://com.umbra.hooks.provider/int/<key>
            val uri = Uri.parse("content://$AUTHORITY/int/$key")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getInt(0)
                }
            }
        } catch (_: Throwable) { }
        return defValue
    }
}
