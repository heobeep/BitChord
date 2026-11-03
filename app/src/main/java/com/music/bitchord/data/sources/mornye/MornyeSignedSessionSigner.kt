package com.music.bitchord.data.sources.mornye

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Builds the HMAC headers required by Mornye's signedSession@1 contract. */
internal object MornyeSignedSessionSigner {
    data class Record(
        val sessionId: String,
        val sessionSecret: String,
    )

    private val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun headers(
        config: MornyeSignedSessionConfig,
        record: Record,
        method: String,
        requestUrl: String,
        body: ByteArray,
        nowMillis: Long,
        nonce: String,
    ): Map<String, String> {
        require(record.sessionId.isNotBlank()) { "Signed session ID is missing" }
        require(record.sessionSecret.isNotBlank()) { "Signed session secret is missing" }
        require(nonce.isNotBlank()) { "Signed request nonce is missing" }
        val url = requireNotNull(requestUrl.toHttpUrlOrNull()) { "Invalid signed request URL" }
        require(url.scheme == "https") { "Signed requests must use HTTPS" }

        val methodUpper = method.trim().uppercase()
        require(methodUpper.matches(Regex("[A-Z]{3,10}"))) { "Invalid signed request method" }
        val timestamp = timestampFormat.format(Instant.ofEpochMilli(nowMillis))
        val bodyHash = sha256Hex(body)
        val window = Math.floorDiv(nowMillis / 1000L, config.timeWindowSeconds)
        val rollingKey = hmac(
            record.sessionSecret.toByteArray(StandardCharsets.UTF_8),
            "$window:${record.sessionId}".toByteArray(StandardCharsets.UTF_8),
        )
        val canonical = listOf(
            config.schemeLabel,
            methodUpper,
            url.encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            record.sessionId,
            config.appVersion,
            config.platform,
        ).joinToString("\n")
        val signature = hmacBase64(
            rollingKey,
            canonical.toByteArray(StandardCharsets.UTF_8),
        )
        val prefix = config.headerPrefix
        return linkedMapOf(
            "${prefix}Session" to record.sessionId,
            "${prefix}Timestamp" to timestamp,
            "${prefix}Nonce" to nonce,
            "${prefix}Body-SHA256" to bodyHash,
            "${prefix}Signature" to signature,
            "${prefix}App-Version" to config.appVersion,
            "${prefix}Platform" to config.platform,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun hmacBase64(key: ByteArray, message: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, message))
}
