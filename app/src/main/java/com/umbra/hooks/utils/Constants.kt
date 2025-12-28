package com.umbra.hooks.utils

object Constants {
    // Shared Preference File Name (Used by XSharedPreferences)
    const val PREFS_FILE = "umbra_settings"

    // Pinterest Keys
    const val KEY_PINTEREST_ENABLED = "enable_pinterest_hook"

    // Gboard Keys
    const val KEY_GBOARD_LIMIT = "gboard_clip_limit"
    const val KEY_GBOARD_RETENTION_DAYS = "gboard_retention_days"
    const val KEY_GBOARD_ENABLED = "gboard_enabled"
    
    // Defaults
    const val DEFAULT_GBOARD_LIMIT = 20
    const val DEFAULT_GBOARD_RETENTION = 3 // Days
}