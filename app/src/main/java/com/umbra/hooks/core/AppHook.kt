package com.umbra.hooks.core

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

interface AppHook {
    val targetPackages: Set<String>
    
    fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam)
    
    /**
     * API 102 Feature: Safely detach and clean up all active hooks 
     * to prevent memory leaks and support Hot Reload.
     */
    fun detachAll()
}
