package com.umbra.hooks.apps

import org.junit.Assert.*
import org.junit.Test
import com.umbra.hooks.apps.GooglePlayRules as R

class GooglePlayRulesTest {
    private fun owner(c: String = "renamed", duplicate: Boolean = false,
                      cacheSecond: Boolean = true, link: Boolean = true): R.OwnerEvidence {
        val a="L"+c+";->lookup(Ljava/lang/String;)Landroid/accounts/Account;"
        val b="L"+c+";->current()Landroid/accounts/Account;"
        val i="L"+c+";->all()Ljava/util/List;"
        val m=listOf(
            R.MethodEvidence(c,"lookup",a,listOf(R.STRING),R.ACCOUNT,calls=setOf(i)),
            R.MethodEvidence(c,"current",b,emptyList(),R.ACCOUNT,setOf(R.CURRENT),if(link)setOf(a) else emptySet()),
            R.MethodEvidence(c,"email","L"+c+";->email()Ljava/lang/String;",emptyList(),R.STRING,calls=setOf(b)),
            R.MethodEvidence(c,"all",i,emptyList(),R.LIST,if(cacheSecond)setOf(R.CACHE_A,R.CACHE_B) else setOf(R.CACHE_A),
                setOf("Landroid/accounts/AccountManager;->getAccounts()[Landroid/accounts/Account;")))
        return R.OwnerEvidence(c,true,if(duplicate)m+m.first().copy(name="other") else m)
    }
    private fun checkout(fields: List<String> = listOf("chosen"))=R.CheckoutEvidence("newModel","ctor",
        setOf(R.BILLING),setOf("Landroid/os/Bundle;->getString(Ljava/lang/String;)Ljava/lang/String;"),fields)

    @Test fun renamedMethods() {
        assertEquals(R.Primary("renamed","lookup","current","email","all"),R.primary(listOf(owner())))
    }
    @Test fun changedCacheTextWithIndependentEdges() {
        assertNotNull(R.primary(listOf(owner(cacheSecond=false))))
    }
    @Test fun rejectAmbiguousOwner() {
        assertNull(R.primary(listOf(owner(),owner("another"))))
    }
    @Test fun rejectAmbiguousLookup() {
        assertNull(R.primary(listOf(owner(duplicate=true))))
    }
    @Test fun rejectMissingInvocation() {
        assertNull(R.primary(listOf(owner(link=false))))
    }
    @Test fun rejectMissingManager() {
        assertNull(R.primary(listOf(owner().copy(managerField=false))))
    }
    @Test fun checkoutFieldRenamed() {
        assertEquals(R.Checkout("newModel","chosen"),R.checkout(listOf(checkout())))
    }
    @Test fun rejectMultipleFields() {
        assertNull(R.checkout(listOf(checkout(listOf("one","two")))))
    }
    @Test fun rejectMultipleConstructors() {
        assertNull(R.checkout(listOf(checkout(),checkout().copy(owner="other"))))
    }
    @Test fun rejectMissingBundleCall() {
        assertNull(R.checkout(listOf(checkout().copy(calls=emptySet()))))
    }
    @Test fun cacheRoundTrip() {
        val m=R.Mapping(R.primary(listOf(owner()))!!,R.checkout(listOf(checkout())))
        assertEquals(m,R.decode(R.encode(m)))
        assertNull(R.decode("renamed"))
        assertNull(R.decode("renamed|lookup|current|email|all|newModel|"))
    }
    @Test fun reviewedRequiresExactIdentity() {
        val hash="77c13c4f0e0de1fdbaf1afea11cdd2dde8f913255137b92d45738a43040ba1f7"
        val version="54.4.11-34 [0] [PR] 1025789771"
        assertEquals("ppr",R.reviewed(85441140,version,hash)?.primary?.owner)
        assertNull(R.reviewed(85441140,version,"0".repeat(64)))
        assertNull(R.reviewed(85441141,version,hash))
        assertNull(R.reviewed(85441140,"different",hash))
    }
}
