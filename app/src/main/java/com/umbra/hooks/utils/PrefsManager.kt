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
            val editor = service.getRemotePreferences(Constants.PREFS_FILE).edit()
                .putInt(Constants.KEY_GBOARD_SPACEBAR_ENABLED, if (settings.enabled) 1 else 0)
                .putInt(Constants.KEY_GBOARD_SPACEBAR_ICON, settings.icon.id)
                .putInt(Constants.KEY_GBOARD_SPACEBAR_CUSTOM_TINT, if (settings.customTint) 1 else 0)
            if (settings.customImage != null) editor.putString(Constants.KEY_GBOARD_SPACEBAR_CUSTOM_IMAGE, settings.customImage)
            else editor.remove(Constants.KEY_GBOARD_SPACEBAR_CUSTOM_IMAGE)
            editor.apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write Gboard spacebar settings.", e)
            false
        }
    }

    fun getString(service: XposedService?, key: String): String? {
        if (service == null) return null
        return try {
            service.getRemotePreferences(Constants.PREFS_FILE).getString(key, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read string from remote prefs. Key: $key", e)
            null
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
