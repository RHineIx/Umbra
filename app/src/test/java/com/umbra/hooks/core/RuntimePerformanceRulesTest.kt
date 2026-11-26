package com.umbra.hooks.core

import com.umbra.hooks.apps.GboardRules
import com.umbra.hooks.billing.BillingReportRules
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RuntimePerformanceRulesTest {
    @Test fun digestMatchesReferenceAcrossBufferBoundaries() {
        val file = File.createTempFile("umbra-digest", ".apk")
        try {
            for (size in listOf(0, 1, 131071, 131072, 131073, 400003)) {
                val data = ByteArray(size) { (it * 37 + 19).toByte() }
                file.writeBytes(data)
                val expected = MessageDigest.getInstance("SHA-256").digest(data)
                    .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
                assertEquals(expected, ApkDigest.sha256(file))
                assertEquals(expected, DynamicCacheRules.sha256(file))
                assertEquals(expected, GboardRules.sha256(file))
            }
        } finally { file.delete() }
    }

    @Test fun flagScanMatchesExistingRules() {
        val flag = "enable_clipboard_entity_extraction"
        for (values in listOf(listOf<Any?>(), listOf(null, "other", flag), listOf(flag, flag),
                listOf("other"), listOf(flag,"enable_clipboard_query_refactoring"))) {
            assertEquals(GboardRules.flagName(values), GboardRules.flagName(values.size) { values[it] })
        }
    }

    @Test fun ambiguousFlagStopsFurtherReflection() {
        val flag = "enable_clipboard_entity_extraction"
        var reads = 0
        assertNull(GboardRules.flagName(5) { index ->
            reads++
            if (index > 1) error("must stop after ambiguity")
            flag
        })
        assertEquals(2, reads)
    }

    @Test fun reportNameLimitsMatchOriginalBehavior() {
        val names = listOf("", " ", "x".repeat(321), "first", "first") +
            (1..200).map { "account"+it }
        val expected = names.filter { it.isNotBlank() && it.length <= 320 }.distinct().take(128).toSet()
        assertEquals(expected, BillingReportRules.normalizeNames(names))
        assertEquals(expected.toList(), BillingReportRules.normalizeNames(names)?.toList())
        assertNull(BillingReportRules.normalizeNames(null))
        assertEquals(emptySet<String>(), BillingReportRules.normalizeNames(emptyList()))
    }

    @Test fun queryBindingCountsRemainAccurate() {
        val args = arrayOf<Any?>(null,null,"a=? AND timestamp >= ? AND b=?",arrayOf("a","old","b"),"timestamp DESC limit 5")
        GboardRules.rewriteQuery(args,12,100,200)
        assertArrayEquals(arrayOf("a","100","b"),args[3] as Array<*>)
        assertEquals("timestamp DESC limit 12",args[4])
    }

    @Test fun queryWithSecondMarkerRemainsUnchanged() {
        val binds = arrayOf("old","new")
        val args = arrayOf<Any?>(null,null,"timestamp >= ? OR timestamp >= ?",binds,null)
        GboardRules.rewriteQuery(args,12,100,200)
        assertSame(binds,args[3])
    }
}
