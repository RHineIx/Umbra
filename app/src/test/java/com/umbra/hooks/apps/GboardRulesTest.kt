package com.umbra.hooks.apps

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GboardRulesTest {
    private val flag = "enable_clipboard_entity_extraction"
    @Test fun exactFlagValueWithoutMemberName() {
        assertEquals(flag, GboardRules.flagName(listOf("other",flag,null)))
    }
    @Test fun unknownOrSubstringNotMatched() {
        assertNull(GboardRules.flagName(listOf("prefix_$flag","another")))
    }
    @Test fun ambiguousFieldsRejected() {
        assertNull(GboardRules.flagName(listOf(flag,flag)))
        assertNull(GboardRules.flagName(listOf(flag,"enable_clipboard_query_refactoring")))
    }
    @Test fun arbitraryToStringNotCalled() {
        val fake = object { override fun toString(): String = error("must not invoke") }
        assertNull(GboardRules.flagName(listOf(fake)))
    }
    @Test fun cacheIncludesSplitAndVersion() {
        val k = GboardRules.buildKey(1,"name",2,"base",listOf("a","b"))
        assertEquals(k,GboardRules.buildKey(1,"name",2,"base",listOf("b","a")))
        assertNotEquals(k,GboardRules.buildKey(1,"name",2,"base",listOf("a","c")))
        assertNotEquals(k,GboardRules.buildKey(1,"name",3,"base",listOf("a","b")))
        assertNotEquals(k,GboardRules.buildKey(1,"name",2,"changed",listOf("a","b")))
        assertNotEquals(k,GboardRules.buildKey(2,"name",2,"base",listOf("a","b")))
    }
    @Test fun fileHashUsesContents() {
        val file=File.createTempFile("gboard-rules",".apk")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",GboardRules.sha256(file))
            file.writeText("abd")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",GboardRules.sha256(file))
        } finally { file.delete() }
    }
    @Test fun smallClipboardLimits() {
        assertEquals(0,GboardRules.timestampSize(0,1))
        assertEquals(5,GboardRules.timestampSize(1,1))
        assertEquals(6,GboardRules.timestampSize(2,1))
        assertEquals(5,GboardRules.timestampSize(100,100))
        assertEquals(101,GboardRules.timestampSize(101,100))
    }
    @Test fun queryRewritesCorrectBindWithoutMutatingOriginal() {
        val bindings=arrayOf("type","100")
        val args=arrayOf<Any?>(null,null,"kind=? AND timestamp >= ?",bindings," TIMESTAMP  desc LIMIT 5 ")
        GboardRules.rewriteQuery(args,50,1000,5000)
        assertArrayEquals(arrayOf("type","4000"),args[3] as Array<*>)
        assertArrayEquals(arrayOf("type","100"),bindings)
        assertEquals("timestamp DESC limit 50",args[4])
    }
    @Test fun quotedOrMultipleMarkersRemainUnchanged() {
        for (selection in listOf("x='?' AND timestamp >= ?","timestamp >= ? OR timestamp >= ?","x=? /* ? */ AND timestamp >= ?")) {
            val bindings=arrayOf("100","200")
            val args=arrayOf<Any?>(null,null,selection,bindings,"other DESC")
            GboardRules.rewriteQuery(args,10,1000,5000)
            assertSame(bindings,args[3])
            assertEquals("other DESC",args[4])
        }
    }
    @Test fun invalidBindingsRemainUnchanged() {
        val bindings=arrayOf("100")
        val args=arrayOf<Any?>(null,null,"kind=? AND timestamp >= ?",bindings,"timestamp DESC limit 10")
        GboardRules.rewriteQuery(args,50,1000,5000)
        assertSame(bindings,args[3])
        assertEquals("timestamp DESC limit 10",args[4])
    }
}
