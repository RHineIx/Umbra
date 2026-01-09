package com.umbra.hooks.core

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface AppHook {
    /** Packages this hook targets */
    val targetPackages: Set<String>
    
    /** Hook entry point */
    fun onLoad(lpparam: XC_LoadPackage.LoadPackageParam)
}