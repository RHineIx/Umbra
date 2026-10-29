package com.umbra.hooks.utils

import com.umbra.hooks.R

data class SpacebarSettings(
    val enabled: Boolean = false,
    val icon: SpacebarIcon = SpacebarIcon.GOOGLE,
    val customImage: String? = null,
    val customTint: Boolean = false,
)

/** Explicit preference IDs remain stable if the picker is reordered. */
enum class SpacebarIcon(val id: Int, val labelRes: Int) {
    GOOGLE(1, R.string.spacebar_icon_google),
    GOOGLE_COLORS(2, R.string.spacebar_icon_google_colors),
    CODE(7, R.string.spacebar_icon_code),
    MEMORY_HEART(8, R.string.spacebar_icon_memory_heart),
    CUSTOM(9, R.string.spacebar_icon_custom);

    companion object {
        fun fromId(id: Int): SpacebarIcon = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}
