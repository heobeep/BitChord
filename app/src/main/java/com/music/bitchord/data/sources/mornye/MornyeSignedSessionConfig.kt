package com.music.bitchord.data.sources.mornye

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * Validated description of Mornye's optional signed-session host contract.
 *
 * Parsing is deliberately separate from the runtime so package validation,
 * compatibility checks and the session coordinator all use the same scope.
 */
internal data class MornyeSignedSessionConfig(
    val namespace: String,
    val baseUrl: String,
    val appVersion: String,
    val platform: String,
    val callbackUrl: String,
    val schemeLabel: String,
    val headerPrefix: String,
    val timeWindowSeconds: Long,
    val bootstrapPath: String,
    val challengePath: String,
    val exchangePath: String,
    val refreshPath: String,
) {
    val scopeKey: String
        get() = listOf(namespace, baseUrl, appVersion, platform)
            .joinToString("\n") { it.trim().lowercase(Locale.ROOT) }

    fun endpoint(path: String): String {
        val base = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid signed-session base URL" }
        val suffix = path.trim()
        require(suffix.isNotEmpty()) { "Signed-session endpoint is empty" }
        if (suffix.startsWith("https://", ignoreCase = true)) {
            val absolute = requireNotNull(suffix.toHttpUrlOrNull()) { "Invalid signed-session endpoint" }
            require(absolute.scheme == "https") { "Signed-session endpoints must use HTTPS" }
            return absolute.toString()
        }
        return base.newBuilder()
            .addPathSegments(suffix.trimStart('/'))
            .build()
            .toString()
    }

    companion object {
        const val FEATURE_SIGNED_SESSION = "signedSession@1"
        const val FEATURE_SESSION_GRANT = "sessionGrant@1"
        const val FEATURE_PREPARED_CONTEXT = "preparedContext@1"

        val SUPPORTED_FEATURES = setOf(
            FEATURE_SIGNED_SESSION,
            FEATURE_SESSION_GRANT,
            FEATURE_PREPARED_CONTEXT,
        )

        fun requiredFeatures(manifest: JSONObject): Set<String> {
            val values = manifest.optJSONArray("requiredRuntimeFeatures") ?: return emptySet()
            return (0 until values.length())
                .map { values.optString(it).trim() }
                .filter(String::isNotEmpty)
                .toSet()
        }

        fun parse(
            manifest: JSONObject,
            allowedDomains: List<String>,
        ): MornyeSignedSessionConfig? {
            val raw = manifest.optJSONObject("signedSession") ?: return null
            val namespace = raw.optString("namespace").trim()
            require(namespace.isNotEmpty()) { "Signed-session namespace is required" }
            require(namespace.length <= 128 && namespace.all {
                it.isLetterOrDigit() || it in ".-_"
            }) { "Invalid signed-session namespace" }

            val baseUrl = raw.optString("baseUrl").trim().trimEnd('/')
            val base = requireNotNull(baseUrl.toHttpUrlOrNull()) {
                "Signed-session baseUrl must be a valid HTTPS URL"
            }
            require(base.scheme == "https" && base.username.isEmpty() && base.password.isEmpty()) {
                "Signed-session baseUrl must use HTTPS and contain no embedded credentials"
            }
            val host = base.host.lowercase(Locale.ROOT)
            require(allowedDomains.any { pattern ->
                val normalized = pattern.lowercase(Locale.ROOT)
                if (normalized.startsWith("*.")) {
                    val suffix = normalized.removePrefix("*")
                    host.endsWith(suffix) && host != suffix.removePrefix(".")
                } else host == normalized
            }) { "Signed-session host $host is missing from the extension's network permissions" }

            val endpoints = raw.optJSONObject("endpoints") ?: JSONObject()
            fun endpoint(name: String, fallback: String): String {
                val value = endpoints.optString(name, fallback).trim()
                require(value.isNotEmpty()) { "Signed-session endpoint $name cannot be empty" }
                if (value.startsWith("https://", ignoreCase = true)) {
                    val url = requireNotNull(value.toHttpUrlOrNull()) {
                        "Invalid signed-session endpoint $name"
                    }
                    require(url.scheme == "https") { "Signed-session endpoint $name must use HTTPS" }
                } else {
                    require(!value.startsWith("//") && !value.contains('\\')) {
                        "Invalid signed-session endpoint $name"
                    }
                }
                return value
            }

            return MornyeSignedSessionConfig(
                namespace = namespace,
                baseUrl = baseUrl,
                appVersion = raw.optString("appVersion", "ext-1.0").ifBlank { "ext-1.0" },
                platform = raw.optString("platform", "extension").ifBlank { "extension" },
                // An empty callback is intentional: BitChord supplies its own app-owned callback.
                callbackUrl = raw.optString("callbackUrl").trim(),
                schemeLabel = raw.optString("schemeLabel", "SPOTIFLAC-HMAC-V1")
                    .ifBlank { "SPOTIFLAC-HMAC-V1" },
                headerPrefix = raw.optString("headerPrefix", "X-Sig-")
                    .ifBlank { "X-Sig-" },
                timeWindowSeconds = raw.optLong("timeWindowSeconds", 300L).takeIf { it > 0 } ?: 300L,
                bootstrapPath = endpoint("bootstrap", "/bootstrap"),
                challengePath = endpoint("challenge", "/challenge"),
                exchangePath = endpoint("exchange", "/session/exchange"),
                refreshPath = endpoint("refresh", ""),
            )
        }
    }
}
