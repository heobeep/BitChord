package com.music.bitchord.data.sources.mornye

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MornyeSignedSessionConfigTest {
    private fun manifest(
        baseUrl: String = "https://api.example.com/v2",
        endpoints: String = """{"bootstrap":"/bootstrap","challenge":"/challenge","exchange":"/session/exchange"}""",
    ) = JSONObject(
        """
        {
          "requiredRuntimeFeatures": ["signedSession@1", "sessionGrant@1"],
          "signedSession": {
            "namespace": "example-provider",
            "baseUrl": "$baseUrl",
            "appVersion": "provider@1.2.3",
            "platform": "extension",
            "schemeLabel": "ZARZ-HMAC-V1",
            "headerPrefix": "X-Zarz-",
            "timeWindowSeconds": 300,
            "endpoints": $endpoints
          }
        }
        """.trimIndent()
    )

    @Test
    fun parsesAmazonStyleSignedSessionScopeAndDefaults() {
        val config = requireNotNull(
            MornyeSignedSessionConfig.parse(manifest(), listOf("api.example.com"))
        )

        assertEquals("example-provider", config.namespace)
        assertEquals("provider@1.2.3", config.appVersion)
        assertEquals("extension", config.platform)
        assertEquals("ZARZ-HMAC-V1", config.schemeLabel)
        assertEquals("X-Zarz-", config.headerPrefix)
        assertEquals(300L, config.timeWindowSeconds)
        assertEquals("https://api.example.com/v2/bootstrap", config.endpoint(config.bootstrapPath))
        assertEquals("https://api.example.com/v2/session/exchange", config.endpoint(config.exchangePath))
        assertTrue(config.refreshPath.isEmpty())
        assertTrue(config.scopeKey.contains("example-provider"))
    }

    @Test
    fun acceptsOnlyManifestAllowedSignedSessionHost() {
        assertThrows(IllegalArgumentException::class.java) {
            MornyeSignedSessionConfig.parse(manifest(), listOf("other.example.com"))
        }
    }

    @Test
    fun rejectsInsecureOrCredentialBearingBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            MornyeSignedSessionConfig.parse(
                manifest(baseUrl = "http://api.example.com/v2"),
                listOf("api.example.com"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MornyeSignedSessionConfig.parse(
                manifest(baseUrl = "https://user:pass@api.example.com/v2"),
                listOf("api.example.com"),
            )
        }
    }

    @Test
    fun wildcardPermissionMatchesSubdomainButNotApex() {
        val subdomain = requireNotNull(
            MornyeSignedSessionConfig.parse(
                manifest(baseUrl = "https://api.example.com/v2"),
                listOf("*.example.com"),
            )
        )
        assertEquals("https://api.example.com/v2/bootstrap", subdomain.endpoint(subdomain.bootstrapPath))

        assertThrows(IllegalArgumentException::class.java) {
            MornyeSignedSessionConfig.parse(
                manifest(baseUrl = "https://example.com/v2"),
                listOf("*.example.com"),
            )
        }
    }

    @Test
    fun readsDeclaredRuntimeFeaturesWithoutInventingDefaults() {
        val declared = MornyeSignedSessionConfig.requiredFeatures(manifest())
        assertTrue("signedSession@1" in declared)
        assertTrue("sessionGrant@1" in declared)
        assertFalse("preparedContext@1" in declared)
        assertNull(MornyeSignedSessionConfig.parse(JSONObject("""{"name":"plain"}""), emptyList()))
    }
}
