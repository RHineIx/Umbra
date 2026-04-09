package com.umbra.hooks.core

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

interface AppHook {
    val targetPackages: Set<String>
    fun handleLoadPackage(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam)
}