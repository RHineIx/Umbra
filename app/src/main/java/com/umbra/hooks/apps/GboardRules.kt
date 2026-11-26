package com.umbra.hooks.apps

import java.io.File
import com.umbra.hooks.core.ApkDigest

/** Pure helpers: no Android, DexKit or hooked application code required. */
internal object GboardRules {
    const val SCHEMA = 3
    const val MAX_CLIPBOARD_LIMIT = 1000
    private val flags = setOf("enable_clipboard_entity_extraction", "enable_clipboard_query_refactoring")
    private val timestampFilter = Regex("\\btimestamp\\s*>=\\s*\\?", RegexOption.IGNORE_CASE)
    private val limitedOrder = Regex("\\s*timestamp\\s+DESC\\s+limit\\s+5\\s*", RegexOption.IGNORE_CASE)

    // Accept only an exact known flag identity, never a substring or arbitrary toString().
    // Two matching fields are ambiguous even if they contain the same value.
    private fun knownFlag(value: Any?): String? =
        when (value) {
            is String -> value
            is Enum<*> -> value.name
            else -> null
        }?.takeIf { it in flags }

    fun flagName(values: Iterable<Any?>): String? {
        var match: String? = null
        for (value in values) {
            val name = knownFlag(value) ?: continue
            if (match != null) return null
            match = name
        }
        return match
    }

    // Read reflection fields directly; do not allocate an intermediate list
    // for every flag read. A second match makes further reads unnecessary.
    fun flagName(count: Int, valueAt: (Int) -> Any?): String? {
        var match: String? = null
        for (index in 0 until count) {
            val name = knownFlag(valueAt(index)) ?: continue
            if (match != null) return null
            match = name
        }
        return match
    }

    fun sha256(file: File): String = ApkDigest.sha256(file)

    fun buildKey(code: Long, name: String?, updated: Long, baseHash: String, splitHashes: List<String>): String =
        listOf(SCHEMA.toString(), code.toString(), name.orEmpty(), updated.toString(),
            baseHash, splitHashes.sorted().joinToString(",")).joinToString(":")

    fun timestampSize(actualSize: Int, limit: Int): Int = when {
        actualSize == 0 -> 0
        actualSize <= limit -> 5
        else -> maxOf(6, actualSize)
    }

    /**
     * Existing clipboard selection and order are the only SQL shapes modified.
     * Reject quoted/commented or repeated timestamp markers rather than guessing
     * their bind position. Keep all other query arguments untouched.
     */
    fun rewriteQuery(args: Array<Any?>, limit: Int, retentionMillis: Long, now: Long) {
        val selection = args.getOrNull(2) as? String
        val selectionArgs = args.getOrNull(3) as? Array<*>
        if (selection != null && selectionArgs != null &&
            selection.none { it == '\'' || it == '"' || it == '\u0060' } &&
            "--" !in selection && "/*" !in selection && "[" !in selection) {
            val marker = timestampFilter.find(selection)
            if (marker != null && marker.next() == null) {
                var index = 0
                var total = 0
                for (position in selection.indices) {
                    if (selection[position] == '?') {
                        total++
                        if (position < marker.range.first) index++
                    }
                }
                if (index in selectionArgs.indices && total == selectionArgs.size &&
                    selectionArgs.all { it == null || it is String }) {
                    val updated = Array(selectionArgs.size) { selectionArgs[it] as? String }
                    updated[index] = (now - retentionMillis).toString()
                    args[3] = updated
                }
            }
        }
        if ((args.getOrNull(4) as? String)?.let(limitedOrder::matches) == true) {
            args[4] = "timestamp DESC limit " + limit.coerceIn(1, MAX_CLIPBOARD_LIMIT)
        }
    }
}
