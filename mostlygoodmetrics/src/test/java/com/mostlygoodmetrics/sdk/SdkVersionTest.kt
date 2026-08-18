package com.mostlygoodmetrics.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the version reported in the User-Agent / X-MGM-SDK-Version headers.
 *
 * SDK_VERSION must be sourced from BuildConfig (which is generated from the
 * single `version = "…"` in build.gradle.kts) so it can never drift back into a
 * second hardcoded literal that under-reports the published release.
 */
class SdkVersionTest {

    @Test
    fun `SDK_VERSION is sourced from BuildConfig, not a hardcoded literal`() {
        assertEquals(BuildConfig.SDK_VERSION, SDK_VERSION)
    }

    @Test
    fun `SDK_VERSION is a non-blank semantic version`() {
        assertTrue(SDK_VERSION.isNotBlank())
        assertTrue(
            "expected a semver-like version but was '$SDK_VERSION'",
            Regex("""^\d+\.\d+\.\d+""").containsMatchIn(SDK_VERSION)
        )
    }
}
