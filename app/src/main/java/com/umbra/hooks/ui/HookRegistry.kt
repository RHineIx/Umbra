package com.umbra.hooks.ui

import com.umbra.hooks.R

data class HookItem(
    val titleResId: Int,
    val descriptionResId: Int,
    val isConfigurable: Boolean,
    val actionIntentClass: Class<*>? = null
)

object HookRegistry {
    fun getHooks(): List<HookItem> {
        return listOf(
            HookItem(
                titleResId = R.string.title_pydroid,
                descriptionResId = R.string.desc_pydroid_features,
                isConfigurable = false
            ),
            HookItem(
                titleResId = R.string.title_grafika,
                descriptionResId = R.string.desc_grafika_features,
                isConfigurable = false
            )
        )
    }
}
