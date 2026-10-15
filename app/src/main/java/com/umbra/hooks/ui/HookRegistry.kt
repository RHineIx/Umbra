package com.umbra.hooks.ui

import com.umbra.hooks.R

data class HookItem(
    val titleResId: Int,
    val descriptionResId: Int,
    val settingsAvailable: Boolean = false
)

object HookRegistry {
    fun getHooks(): List<HookItem> {
        return listOf(
            HookItem(
                titleResId = R.string.title_gboard,
                descriptionResId = R.string.desc_gboard_features,
                settingsAvailable = true
            ),
            HookItem(
                titleResId = R.string.title_pydroid,
                descriptionResId = R.string.desc_pydroid_features
            ),
            HookItem(
                titleResId = R.string.title_grafika,
                descriptionResId = R.string.desc_grafika_features
            ),
            HookItem(
                titleResId = R.string.title_showly,
                descriptionResId = R.string.desc_showly_features
            )
        )
    }
}
