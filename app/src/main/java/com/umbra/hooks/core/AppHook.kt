package com.umbra.hooks.core

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

interface AppHook {
    /** Packages this hook targets */
    val targetPackages: Set<String>
    
    /** * Hook entry point in modern LibXposed API.
     * @param module The main XposedModule instance used to register hooks and log.
     * @param param The PackageLoadedParam containing defaultClassLoader and package info.
     */
    fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam)
}