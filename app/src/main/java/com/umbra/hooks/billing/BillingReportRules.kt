package com.umbra.hooks.billing

/** Preserve the bridge's existing limits without several intermediate collections. */
internal object BillingReportRules {
    fun normalizeNames(names: List<String>?): Set<String>? {
        if (names == null) return null
        val result = LinkedHashSet<String>()
        for (name in names) {
            if (name.isNotBlank() && name.length <= 320) {
                result.add(name)
                if (result.size == 128) break
            }
        }
        return result
    }
}
