package com.umbra.hooks.utils

import io.github.libxposed.api.XposedModule
import io.github.libxposed.service.XposedService

object PrefsManager {

    fun putInt(service: XposedService?, key: String, value: Int): Boolean {
        if (service == null) return false
        return try {
            val prefs = service.getRemotePreferences(Constants.PREFS_FILE)
            prefs.edit().putInt(key, value).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getInt(service: XposedService?, key: String, defValue: Int): Int {
        if (service == null) return defValue
        return try {
            val prefs = service.getRemotePreferences(Constants.PREFS_FILE)
            prefs.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }
    }

    fun getHookInt(module: XposedModule, key: String, defValue: Int): Int {
        return try {
            val prefs = module.getRemotePreferences(Constants.PREFS_FILE)
            prefs.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }
    }
}