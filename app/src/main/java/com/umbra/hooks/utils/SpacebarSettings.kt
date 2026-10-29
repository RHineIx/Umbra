package com.umbra.hooks.utils

import com.umbra.hooks.R

data class SpacebarSettings(
    val enabled: Boolean = false,
    val icon: SpacebarIcon = SpacebarIcon.GOOGLE,
)

/** Explicit preference IDs remain stable if the picker is reordered. */
enum class SpacebarIcon(val id: Int, val labelRes: Int) {
    GOOGLE(1, R.string.spacebar_icon_google),
    GOOGLE_COLORS(2, R.string.spacebar_icon_google_colors),
    UMBRA(3, R.string.spacebar_icon_umbra),
    STAR(4, R.string.spacebar_icon_star),
    HEART(5, R.string.spacebar_icon_heart),
    SPARKLE(6, R.string.spacebar_icon_sparkle);

    companion object {
        fun fromId(id: Int): SpacebarIcon = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}
