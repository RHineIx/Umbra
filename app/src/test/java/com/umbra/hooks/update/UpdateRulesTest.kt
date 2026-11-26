package com.umbra.hooks.update

import org.junit.Assert.*
import org.junit.Test

class UpdateRulesTest {
    @Test fun acceptsOnlyOfficialReleaseAsset() {
        val tag = "v3.8.3"
        val name = "Umbra-v3.8.3-app-release.apk"
        val url = "https://github.com/RHineIx/Umbra/releases/download/$tag/$name"
        assertEquals(url, UpdateRules.downloadUrl(tag, name, url))
        assertNull(UpdateRules.downloadUrl(tag, "other.apk", url))
        assertNull(UpdateRules.downloadUrl(tag, name, url.replace("github.com", "example.com")))
        assertNull(UpdateRules.downloadUrl(tag, name, "$url?redirect=1"))
        assertNull(UpdateRules.downloadUrl("v3.8.2", name, url))
        assertNull(UpdateRules.downloadUrl("v3.8.3-rc.1", name, url))
    }

    @Test fun newerStableRelease() {
        val result = UpdateRules.available("3.8.2", "v3.8.3", false, false)
        assertEquals("v3.8.3", result?.tag)
        assertEquals("https://github.com/RHineIx/Umbra/releases/tag/v3.8.3", result?.url)
    }

    @Test fun numericVersionOrdering() {
        assertNotNull(UpdateRules.available("3.9.9", "v3.10.0", false, false))
        assertNotNull(UpdateRules.available("3.8.9", "v3.8.10", false, false))
        assertNotNull(UpdateRules.available("3.99.99", "v4.0.0", false, false))
    }

    @Test fun sameOrOlderDoesNotNotify() {
        assertNull(UpdateRules.available("3.8.2", "v3.8.2", false, false))
        assertNull(UpdateRules.available("3.8.2", "v3.8.1", false, false))
    }

    @Test fun ignoresDraftAndPrerelease() {
        assertNull(UpdateRules.available("3.8.2", "v3.8.3", true, false))
        assertNull(UpdateRules.available("3.8.2", "v3.8.3", false, true))
        assertNull(UpdateRules.available("3.8.2", "v3.8.3-rc.1", false, false))
    }

    @Test fun stableSupersedesInstalledPrerelease() {
        assertNotNull(UpdateRules.available("3.8.2-rc.1", "v3.8.2", false, false))
    }

    @Test fun malformedOrUnsafeTagsAreRejected() {
        for (tag in listOf("v3.8", "v3.08.3", "v3.8.3/../../", "v999999999999.8.3", "3.8.3", "v3.8.3?x=1")) {
            assertNull(UpdateRules.available("3.8.2", tag, false, false))
        }
        assertNull(UpdateRules.available("Unknown", "v3.8.3", false, false))
    }
}
