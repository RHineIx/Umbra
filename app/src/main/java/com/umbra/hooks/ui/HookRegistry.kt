package com.umbra.hooks.ui

import com.umbra.hooks.R

data class HookItem(
    val titleResId: Int,
    val descriptionResId: Int
)

object HookRegistry {
    fun getHooks(): List<HookItem> {
        return listOf(
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