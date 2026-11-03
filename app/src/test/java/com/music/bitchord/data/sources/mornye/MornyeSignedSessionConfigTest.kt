package com.music.bitchord.data.sources.mornye

import org.json.JSONArray
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
    ): JSONObject = JSONObject()
        .put("requiredRuntimeFeatures", JSONArray().put("signedSession@1").put("sessionGrant@1"))
        .put(
            "signedSession",
            JSONObject()
                .put("namespace", "example-provider")
                .put("baseUrl", baseUrl)
                .put("appVersion", "provider@1.2.3")
                .put("platform", "extension")
                .put("schemeLabel", "ZARZ-HMAC-V1")
                .put("headerPrefix", "X-Zarz-")
                .put("timeWindowSeconds", 300)
                .put("endpoints", JSONObject().put("bootstrap", "/bootstrap").put("challenge", "/challenge").put("exchange", "/session/exchange")),
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
        assertNull(MornyeSignedSessionConfig.parse(JSONObject("""{"name":"plain"}"""), emptyList()))
    }

    @Test
    fun signedSessionHeadersMatchDeterministicHmacVector() {
        val config = requireNotNull(
            MornyeSignedSessionConfig.parse(
                JSONObject(
                    """
                    {
                      "signedSession": {
                        "namespace": "example-provider",
                        "baseUrl": "https://api.example.com/v2",
                        "appVersion": "provider@1.0.0",
                        "platform": "extension",
                        "schemeLabel": "ZARZ-HMAC-V1",
                        "headerPrefix": "X-Zarz-",
                        "timeWindowSeconds": 300,
                        "endpoints": {"bootstrap":"/bootstrap","challenge":"/challenge","exchange":"/session/exchange"}
                      }
                    }
                    """.trimIndent()
                ),
                listOf("api.example.com"),
            )
        )
        val headers = MornyeSignedSessionSigner.headers(
            config = config,
            record = MornyeSignedSessionSigner.Record("session-id", "session-secret"),
            method = "POST",
            requestUrl = "https://api.example.com/v2/tracks",
            body = """{"track":"123"}""".toByteArray(),
            nowMillis = 1_760_000_000_123L,
            nonce = "nonce-123",
        )

        assertEquals("2025-10-09T08:53:20.123Z", headers["X-Zarz-Timestamp"])
        assertEquals(
            "22db04e746cf50046345ce3b147bc417cda6af3eb52a34fb7e0a7b339d64a5a0",
            headers["X-Zarz-Body-SHA256"],
        )
        assertEquals("J0CaqPSbZ5zRdXsZKqz_f7kwRQioRETrGMu2kjBvyik", headers["X-Zarz-Signature"])
    }

}
