package com.umbra.hooks.core

import java.io.File

/** Pure cache rules, independent of Android and of a particular target. */
internal object DynamicCacheRules {
    const val SCHEMA = 2
    const val BUILD_KEY = "umbra_engine_build"

    fun sha256(file: File): String = ApkDigest.sha256(file)

    fun buildKey(packageName: String, code: Long, name: String?, updated: Long,
                 baseHash: String, splitHashes: List<String>, rulesVersion: Int): String =
        listOf(SCHEMA.toString(), rulesVersion.toString(), packageName, code.toString(),
            name.orEmpty(), updated.toString(), baseHash,
            splitHashes.sorted().joinToString(",")).joinToString(":")

    fun hasTargets(values: Map<String, *>): Boolean = values.any { (key, value) ->
        key != BUILD_KEY && key != "app_version" && when (value) {
            is String -> value.isNotBlank()
            is Set<*> -> value.any { it is String && it.isNotBlank() }
            else -> false
        }
    }
}
