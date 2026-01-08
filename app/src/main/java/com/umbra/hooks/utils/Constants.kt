package com.umbra.hooks.utils

object Constants {
    // Shared Preference File Name (Used by XSharedPreferences)
    const val PREFS_FILE = "umbra_settings"

    // Gboard Keys
    const val KEY_GBOARD_LIMIT = "gboard_clip_limit"
    const val KEY_GBOARD_RETENTION_DAYS = "gboard_retention_days"
    const val KEY_GBOARD_ENABLED = "gboard_enabled"
    
    // Defaults
    const val DEFAULT_GBOARD_LIMIT = 20
    const val DEFAULT_GBOARD_RETENTION = 3 // Days

    // Pydroid Keys
    const val KEY_PYDROID_ENABLED = "pydroid_enabled"
    const val KEY_PYDROID_PREMIUM = "pydroid_premium"
    const val KEY_PYDROID_NO_ADS = "pydroid_no_ads"
    const val KEY_PYDROID_NO_JUMP = "pydroid_no_jump"
}