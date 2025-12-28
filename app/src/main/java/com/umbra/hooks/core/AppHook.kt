package com.umbra.hooks.core

import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

interface AppHook {

    /** Packages this hook targets */
    val targetPackages: Set<String>

    /** Whether this hook is enabled based on preferences */
    fun isEnabled(prefs: XSharedPreferences): Boolean

    /** Hook entry point */
    fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam)
}