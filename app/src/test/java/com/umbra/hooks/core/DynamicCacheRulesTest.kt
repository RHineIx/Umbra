package com.umbra.hooks.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DynamicCacheRulesTest {
    private fun key(code: Long = 1, updated: Long = 2, base: String = "base",
                    splits: List<String> = listOf("a","b"), rules: Int = 1,
                    pkg: String = "test", name: String = "v") =
        DynamicCacheRules.buildKey(pkg,code,name,updated,base,splits,rules)

    @Test fun exactBuildIdentity() {
        assertEquals(key(),key(splits=listOf("b","a")))
        assertNotEquals(key(),key(splits=listOf("a","c")))
        assertNotEquals(key(),key(base="changed"))
        assertNotEquals(key(),key(code=3))
        assertNotEquals(key(),key(updated=3))
        assertNotEquals(key(),key(rules=2))
        assertNotEquals(key(),key(pkg="other"))
        assertNotEquals(key(),key(name="other"))
    }

    @Test fun emptyOrMetadataOnlyNotUsable() {
        assertFalse(DynamicCacheRules.hasTargets(emptyMap<String, Any>()))
        assertFalse(DynamicCacheRules.hasTargets(mapOf(DynamicCacheRules.BUILD_KEY to "id","app_version" to 1)))
        assertFalse(DynamicCacheRules.hasTargets(mapOf("targets" to emptySet<String>(),"other" to " ")))
    }

    @Test fun nonemptyTargets() {
        assertTrue(DynamicCacheRules.hasTargets(mapOf("targets" to setOf("a|b"))))
        assertTrue(DynamicCacheRules.hasTargets(mapOf("target" to "a")))
        assertFalse(DynamicCacheRules.hasTargets(mapOf("targets" to setOf(" "))))
    }

    @Test fun sha256Content() {
        val f = File.createTempFile("umbra-core", ".apk")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",DynamicCacheRules.sha256(f))
            f.writeText("abcd")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",DynamicCacheRules.sha256(f))
        } finally { f.delete() }
    }
}
