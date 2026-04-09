package com.umbra.hooks.utils

import io.github.libxposed.api.XposedModule
import io.github.libxposed.service.XposedService

object PrefsManager {

    private const val PREFS_NAME = "umbra_settings"

    fun putInt(service: XposedService?, key: String, value: Int): Boolean {
        if (service == null) return false
        return try {
            val prefs = service.getRemotePreferences(PREFS_NAME)
            prefs.edit().putInt(key, value).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getInt(service: XposedService?, key: String, defValue: Int): Int {
        if (service == null) return defValue
        return try {
            val prefs = service.getRemotePreferences(PREFS_NAME)
            prefs.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }
    }

    fun getHookInt(module: XposedModule, key: String, defValue: Int): Int {
        return try {
            val prefs = module.getRemotePreferences(PREFS_NAME)
            prefs.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }
    }
}