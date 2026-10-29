package com.umbra.hooks.utils

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.service.XposedService

object PrefsManager {

    private const val TAG = "UMBRA-PREFS"

    fun putInt(service: XposedService?, key: String, value: Int): Boolean {
        if (service == null) return false
        return try {
            val prefs = service.getRemotePreferences(Constants.PREFS_FILE)
            prefs.edit().putInt(key, value).apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write int to remote prefs. Key: $key", e)
            false
        }
    }

    fun getInt(service: XposedService?, key: String, defValue: Int): Int {
        if (service == null) return defValue
        return try {
            val prefs = service.getRemotePreferences(Constants.PREFS_FILE)
            prefs.getInt(key, defValue)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read int from remote prefs. Key: $key", e)
            defValue
        }
    }

    fun putGboardClipboardSettings(
        service: XposedService?,
        clipboardLimit: Int,
        retentionDays: Int,
    ): Boolean {
        if (service == null) return false
        return try {
            service.getRemotePreferences(Constants.PREFS_FILE).edit()
                .putInt(Constants.KEY_GBOARD_CLIPBOARD_LIMIT, clipboardLimit)
                .putInt(Constants.KEY_GBOARD_CLIPBOARD_RETENTION_DAYS, retentionDays)
                .apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write Gboard clipboard settings.", e)
            false
        }
    }

    fun putGboardSpacebarSettings(service: XposedService?, settings: SpacebarSettings): Boolean {
        if (service == null) return false
        return try {
            service.getRemotePreferences(Constants.PREFS_FILE).edit()
                .putInt(Constants.KEY_GBOARD_SPACEBAR_ENABLED, if (settings.enabled) 1 else 0)
                .putInt(Constants.KEY_GBOARD_SPACEBAR_ICON, settings.icon.id)
                .apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write Gboard spacebar settings.", e)
            false
        }
    }

    fun getHookInt(module: XposedModule, key: String, defValue: Int): Int {
        return try {
            val prefs = module.getRemotePreferences(Constants.PREFS_FILE)
            prefs.getInt(key, defValue)
        } catch (e: Throwable) {
            module.log(Log.ERROR, TAG, "Failed to read int from hook prefs. Key: $key", e)
            defValue
        }
    }
}
