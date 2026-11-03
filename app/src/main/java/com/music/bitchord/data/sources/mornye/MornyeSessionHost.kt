package com.music.bitchord.data.sources.mornye

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.auth.EncryptedPrefs
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Host implementation for Mornye's signed-session contract.
 *
 * Session IDs and secrets are encrypted at rest. Verification grants are
 * bound to a random state and exchanged only after the matching app callback.
 */
internal class MornyeSessionHost(
    context: Context,
    private val extensionId: String,
    manifest: JSONObject,
    private val allowedDomains: List<String>,
) {
    private val appContext = context.applicationContext
    private val config = manifest.optJSONObject("signedSession")?.let(::readConfig)
    private val prefs = EncryptedPrefs.openRequired(
        appContext,
        "bitchord_mornye_sessions",
        "bitchord_mornye_sessions_plain",
    )
    private val recordKey = "scope." + (config?.let(::scopeHash) ?: extensionId)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Config(
        val namespace: String,
        val baseUrl: String,
        val appVersion: String,
        val platform: String,
        val schemeLabel: String,
        val headerPrefix: String,
        val timeWindowSeconds: Long,
        val bootstrapPath: String,
        val challengePath: String,
        val exchangePath: String,
        val refreshPath: String,
        val callbackUrl: String,
    )

    private data class HttpResult(
        val status: Int,
        val body: String,
        val headers: JSONObject,
    )

    private fun readConfig(json: JSONObject): Config {
        val endpoints = json.optJSONObject("endpoints") ?: JSONObject()
        val base = json.optString("baseUrl").trim()
        val parsed = base.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("signedSession.baseUrl is invalid")
        require(parsed.isHttps) { "signedSession.baseUrl must use HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "signedSession.baseUrl must not contain embedded credentials"
        }
        require(parsed.encodedQuery == null && parsed.encodedFragment == null) {
            "signedSession.baseUrl must not contain a query or fragment"
        }
        require(isDomainAllowed(parsed.host)) {
            "signedSession.baseUrl host is missing from permissions.network"
        }
        val namespace = json.optString("namespace").trim().lowercase(Locale.ROOT)
        require(namespace.isNotBlank()) { "signedSession.namespace is required" }
        return Config(
            namespace = namespace,
            baseUrl = base.trimEnd('/') + "/",
            appVersion = json.optString("appVersion").ifBlank { "ext-1.0" },
            platform = json.optString("platform").ifBlank { "extension" },
            schemeLabel = json.optString("schemeLabel").ifBlank { "SPOTIFLAC-HMAC-V1" },
            headerPrefix = json.optString("headerPrefix").ifBlank { "X-Sig-" },
            timeWindowSeconds = json.optLong("timeWindowSeconds", 300L).takeIf { it > 0 } ?: 300L,
            bootstrapPath = endpoints.optString("bootstrap").ifBlank { "/bootstrap" },
            challengePath = endpoints.optString("challenge").ifBlank { "/challenge" },
            exchangePath = endpoints.optString("exchange").ifBlank { "/session/exchange" },
            refreshPath = endpoints.optString("refresh"),
            // Verification callbacks are app-owned; extensions cannot redirect grants elsewhere.
            callbackUrl = "mornye://session-grant",
        )
    }

    private fun isDomainAllowed(host: String): Boolean = allowedDomains.any { pattern ->
        val allowed = pattern.trim().lowercase(Locale.ROOT)
        if (allowed == "*") true
        else if (allowed.startsWith("*.")) {
            val suffix = allowed.removePrefix("*")
            host.endsWith(suffix) && host != suffix.removePrefix(".")
        } else host.equals(allowed, ignoreCase = true)
    }

    fun install(quickJs: QuickJs) {
        quickJs.define("__mornye_session") {
            function("enabled", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    if (config == null) "false" else "true"
            })
            function("call", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString().orEmpty()
                    val rawArguments = args.getOrNull(1)?.toString() ?: "[]"
                    return call(method, rawArguments).toString()
                }
            })
        }
    }

    @Synchronized
    private fun call(method: String, rawArguments: String): JSONObject {
        if (method == "status") return status()
        if (method == "clear") {
            clear()
            return JSONObject().put("success", true)
        }
        if (config == null) return error("signed sessions are not configured")
        return try {
            val args = JSONArray(rawArguments)
            when (method) {
                "signedFetch" -> {
                    val headers = args.optJSONObject(3) ?: JSONObject()
                    signedFetch(
                        args.optString(0),
                        args.optString(1),
                        if (args.isNull(2)) "" else args.optString(2),
                        headers,
                    )
                }
                "completeGrant" -> {
                    val grant = args.optString(0)
                    completeGrant(grant, readRecord().optString("pendingState"))
                    JSONObject().put("success", true)
                }
                else -> error("unknown session method")
            }
        } catch (failure: Throwable) {
            error(failure.message ?: "signed-session operation failed")
        }
    }

    @Synchronized
    private fun status(): JSONObject {
        val record = readRecord()
        val sessionId = record.optString("sessionId")
        val secret = record.optString("sessionSecret")
        val expiry = record.optString("expiresAt")
        val valid = sessionId.isNotBlank() && secret.isNotBlank() && !isExpired(expiry)
        return JSONObject()
            .put("enabled", config != null)
            .put("authenticated", valid)
            .put("expires_at", expiry)
            .put("needsVerification", record.optString("pendingState").isNotBlank())
            .put("auth_url", record.optString("authUrl"))
    }

    @Synchronized
    private fun clear() {
        val record = readRecord()
        record.remove("sessionId")
        record.remove("sessionSecret")
        record.remove("expiresAt")
        record.remove("pendingState")
        record.remove("authUrl")
        record.remove("openedAt")
        writeRecord(record)
    }

    @Synchronized
    private fun signedFetch(
        methodValue: String,
        path: String,
        body: String,
        extraHeaders: JSONObject,
    ): JSONObject {
        val cfg = config ?: return error("signed sessions are not configured")
        val method = methodValue.trim().uppercase(Locale.ROOT)
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "unsupported signed-session HTTP method"
        }
        require(path.isNotBlank()) { "signed-session request path is required" }

        var record = readRecord()
        if (!recordUsable(record)) {
            val verification = bootstrap(cfg, record)
            if (verification != null) return verification
            record = readRecord()
            if (!recordUsable(record)) return error("signed session is not authenticated")
        } else {
            refreshIfDue(cfg, record)
            record = readRecord()
        }

        var response = signedRequest(cfg, record, method, path, body, extraHeaders)
        if (response.status == 401 || response.status == 428) {
            val contract = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
            val code = contract.optString("code").uppercase(Locale.ROOT)
            val action = contract.optString("action").lowercase(Locale.ROOT)
            val origin = contract.optString("origin").lowercase(Locale.ROOT)
            val refreshNeeded = response.status == 401 &&
                (code == "SESSION_INVALID" || action == "bootstrap_session")
            val verificationNeeded = response.status == 428 &&
                (code == "VERIFY_REQUIRED" || action == "verify" || origin == "gateway")
            if (refreshNeeded || verificationNeeded) {
                if (refreshNeeded) {
                    record.remove("sessionId")
                    record.remove("sessionSecret")
                    record.remove("expiresAt")
                    writeRecord(record)
                }
                val verification = bootstrap(cfg, record)
                if (verification != null) return verification
                record = readRecord()
                if (recordUsable(record)) {
                    response = signedRequest(cfg, record, method, path, body, extraHeaders)
                }
            }
        }

        return responseValue(response)
    }

    /**
     * Returns a verification result when the caller must leave the app, or
     * null when bootstrap stored an immediately usable session.
     */
    private fun bootstrap(cfg: Config, oldRecord: JSONObject): JSONObject? {
        val currentPending = oldRecord.optString("pendingState")
        if (currentPending.isNotBlank()) {
            openVerificationIfNeeded(oldRecord)
            return verificationResult(oldRecord.optString("authUrl"))
        }

        val record = oldRecord
        val installId = record.optString("installId").ifBlank { randomHex(16) }
        record.put("installId", installId)
        writeRecord(record)

        val url = endpoint(cfg, cfg.bootstrapPath).newBuilder()
            .addQueryParameter("app_version", cfg.appVersion)
            .addQueryParameter("install_id", installId)
            .build()
        val response = execute(Request.Builder().url(url).get().build())
        require(response.status in 200..299) {
            "signed-session bootstrap returned HTTP " + response.status
        }
        val payload = JSONObject(response.body)
        val sessionId = payload.optString("session_id")
        val sessionSecret = payload.optString("session_secret")
        val expiresAt = payload.optString("expires_at")
        if (sessionId.isNotBlank() && sessionSecret.isNotBlank() && expiresAt.isNotBlank()) {
            record.put("sessionId", sessionId)
            record.put("sessionSecret", sessionSecret)
            record.put("expiresAt", expiresAt)
            record.remove("pendingState")
            record.remove("authUrl")
            record.remove("openedAt")
            writeRecord(record)
            return null
        }

        var authUrl = payload.optString("auth_url").ifBlank {
            payload.optString("challenge_url")
        }
        var state = randomHex(24)
        if (authUrl.isNotBlank()) {
            val existingState = Uri.parse(authUrl).getQueryParameter("state").orEmpty()
            if (existingState.isNotBlank()) {
                state = existingState
            } else {
                authUrl = Uri.parse(authUrl).buildUpon()
                    .appendQueryParameter("state", state)
                    .build().toString()
            }
        } else {
            val challengeId = payload.optString("challenge_id")
            if (challengeId.isNotBlank()) {
                val callback = Uri.parse(cfg.callbackUrl).buildUpon()
                    .appendQueryParameter("cb_version", "v2grant")
                    .appendQueryParameter("state", state)
                    .build().toString()
                authUrl = endpoint(cfg, cfg.challengePath).newBuilder()
                    .addQueryParameter("id", challengeId)
                    .addQueryParameter("cb", callback)
                    .build().toString()
            }
        }
        require(authUrl.isNotBlank()) {
            "signed-session bootstrap returned neither a session nor a verification challenge"
        }
        val authUri = Uri.parse(authUrl)
        require(authUri.scheme.equals("https", true)) {
            "verification URL must use HTTPS"
        }
        require(authUri.userInfo.isNullOrEmpty() && isDomainAllowed(authUri.host.orEmpty())) {
            "verification URL host must be declared in permissions.network and contain no credentials"
        }
        record.put("pendingState", state)
        record.put("authUrl", authUrl)
        record.put("openedAt", 0L)
        writeRecord(record)
        openVerificationIfNeeded(record)
        return verificationResult(authUrl)
    }

    private fun openVerificationIfNeeded(record: JSONObject) {
        val url = record.optString("authUrl")
        if (url.isBlank()) return
        val lastOpened = record.optLong("openedAt", 0L)
        val now = System.currentTimeMillis()
        if (lastOpened > 0 && now - lastOpened < 60_000L) return
        record.put("openedAt", now)
        writeRecord(record)
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    @Synchronized
    private fun completeGrant(grant: String, state: String) {
        val cfg = config ?: throw IllegalStateException("signed sessions are not configured")
        require(grant.isNotBlank()) { "verification callback did not include a grant" }
        val record = readRecord()
        require(state.isNotBlank() && state == record.optString("pendingState")) {
            "verification callback state did not match the pending request"
        }
        val installId = record.optString("installId")
        require(installId.isNotBlank()) { "signed-session install id is missing" }
        val requestBody = JSONObject()
            .put("grant", grant)
            .put("install_id", installId)
            .put("app_version", cfg.appVersion)
            .put("platform", cfg.platform)
            .toString()
        val request = Request.Builder()
            .url(endpoint(cfg, cfg.exchangePath))
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .build()
        val response = execute(request)
        require(response.status in 200..299) {
            "signed-session grant exchange returned HTTP " + response.status
        }
        val payload = JSONObject(response.body)
        val sessionId = payload.optString("session_id")
        val sessionSecret = payload.optString("session_secret")
        val expiresAt = payload.optString("expires_at")
        require(sessionId.isNotBlank() && sessionSecret.isNotBlank() && expiresAt.isNotBlank()) {
            "signed-session grant exchange did not return session credentials"
        }
        record.put("sessionId", sessionId)
        record.put("sessionSecret", sessionSecret)
        record.put("expiresAt", expiresAt)
        record.remove("pendingState")
        record.remove("authUrl")
        record.remove("openedAt")
        writeRecord(record)
    }

    private fun refreshIfDue(cfg: Config, record: JSONObject) {
        if (cfg.refreshPath.isBlank()) return
        val expiry = parseExpiry(record.optString("expiresAt")) ?: return
        val now = System.currentTimeMillis()
        if (expiry <= now || expiry - now > 3_600_000L) return
        val body = JSONObject().put("install_id", record.optString("installId")).toString()
        val response = runCatching {
            signedRequest(cfg, record, "POST", cfg.refreshPath, body, JSONObject())
        }.getOrNull() ?: return
        if (response.status !in 200..299) return
        val payload = runCatching { JSONObject(response.body) }.getOrNull() ?: return
        if (payload.optString("session_id").isNotBlank()) {
            record.put("sessionId", payload.optString("session_id"))
        }
        if (payload.optString("session_secret").isNotBlank()) {
            record.put("sessionSecret", payload.optString("session_secret"))
        }
        if (payload.optString("expires_at").isNotBlank()) {
            record.put("expiresAt", payload.optString("expires_at"))
        }
        writeRecord(record)
    }

    private fun signedRequest(
        cfg: Config,
        record: JSONObject,
        method: String,
        path: String,
        body: String,
        extraHeaders: JSONObject,
    ): HttpResult {
        val url = endpoint(cfg, path)
        val nonce = randomHex(16)
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))
        val bodyHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val window = (now / 1000L) / cfg.timeWindowSeconds
        val sessionId = record.optString("sessionId")
        val secret = record.optString("sessionSecret")
        val rollingKey = base64Url(hmac(secret.toByteArray(Charsets.UTF_8), (window.toString() + ":" + sessionId).toByteArray(Charsets.UTF_8)))
        val signing = listOf(
            cfg.schemeLabel,
            method,
            url.encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            sessionId,
            cfg.appVersion,
            cfg.platform,
        ).joinToString("\n")
        val signature = base64Url(hmac(rollingKey.toByteArray(Charsets.UTF_8), signing.toByteArray(Charsets.UTF_8)))

        val builder = Request.Builder().url(url)
            .header(cfg.headerPrefix + "Session", sessionId)
            .header(cfg.headerPrefix + "Timestamp", timestamp)
            .header(cfg.headerPrefix + "Nonce", nonce)
            .header(cfg.headerPrefix + "Body-SHA256", bodyHash)
            .header(cfg.headerPrefix + "Signature", signature)
            .header(cfg.headerPrefix + "App-Version", cfg.appVersion)
            .header(cfg.headerPrefix + "Platform", cfg.platform)
        val headers = extraHeaders.keys()
        while (headers.hasNext()) {
            val name = headers.next()
            builder.header(name, extraHeaders.optString(name))
        }
        val requestBody = if (method in setOf("POST", "PUT", "PATCH")) {
            body.toRequestBody("application/json; charset=utf-8".toMediaType())
        } else null
        builder.method(method, requestBody)
        return execute(builder.build())
    }

    private fun execute(request: Request): HttpResult {
        assertAllowed(request.url)
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.source()?.readByteArray(MAX_RESPONSE_BYTES + 1L) ?: ByteArray(0)
            require(bytes.size <= MAX_RESPONSE_BYTES) { "signed-session response exceeded 4 MB" }
            val headers = JSONObject()
            response.headers.names().forEach { name ->
                val values = JSONArray()
                response.headers.values(name).forEach(values::put)
                headers.put(name, if (values.length() == 1) values.getString(0) else values)
            }
            return HttpResult(response.code, bytes.toString(Charsets.UTF_8), headers)
        }
    }

    private fun assertAllowed(url: HttpUrl) {
        require(url.isHttps) { "signed-session network requests must use HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "signed-session network requests must not contain embedded credentials"
        }
        require(isDomainAllowed(url.host)) {
            "signed-session domain is not declared in permissions.network: " + url.host
        }
        val addresses = InetAddress.getAllByName(url.host)
        require(addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }) { "private network destinations are blocked" }
    }

    private fun endpoint(cfg: Config, path: String): HttpUrl {
        val base = cfg.baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("signed-session base URL is invalid")
        val raw = path.trim()
        if (raw.startsWith("https://", ignoreCase = true)) {
            val absolute = raw.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("signed-session endpoint URL is invalid")
            assertAllowed(absolute)
            return absolute
        }
        val relative = raw.trimStart('/')
        return base.resolve(relative)
            ?: throw IllegalArgumentException("signed-session endpoint path is invalid")
    }

    private fun responseValue(response: HttpResult): JSONObject {
        val json = JSONObject()
            .put("ok", response.status in 200..299)
            .put("status", response.status)
            .put("statusCode", response.status)
            .put("body", response.body)
            .put("headers", response.headers)
        if (response.status !in 200..299) {
            val contract = runCatching { JSONObject(response.body) }.getOrNull()
            if (contract != null) {
                json.put("error", contract.optString("error").ifBlank { "HTTP " + response.status })
                json.put("code", contract.optString("code"))
                json.put("retryable", contract.optBoolean("retryable", false))
                json.put("retryMode", contract.optString("retry_mode"))
                json.put("retryAfterSeconds", contract.optInt("retry_after_seconds", 0))
            } else {
                json.put("error", "HTTP " + response.status)
            }
        }
        return json
    }

    private fun verificationResult(url: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("status", 428)
            .put("statusCode", 428)
            .put("error", "VERIFY_REQUIRED")
            .put("code", "VERIFY_REQUIRED")
            .put("needsVerification", true)
            .put("auth_url", url)
            .put("open_auth_url", url)

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun recordUsable(record: JSONObject): Boolean =
        record.optString("sessionId").isNotBlank() &&
            record.optString("sessionSecret").isNotBlank() &&
            !isExpired(record.optString("expiresAt"))

    private fun isExpired(value: String): Boolean =
        parseExpiry(value)?.let { it <= System.currentTimeMillis() } ?: false

    private fun parseExpiry(value: String): Long? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching {
            if (text.all { it.isDigit() }) {
                val number = text.toLong()
                if (number < 100_000_000_000L) number * 1000L else number
            } else {
                val normalized = if (text.endsWith("Z")) text else text
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                try {
                    format.parse(normalized)?.time
                } catch (_: Throwable) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(normalized)?.time
                }
            }
        }.getOrNull()
    }

    private fun readRecord(): JSONObject {
        val raw = prefs.getString(recordKey, null)
        val record = runCatching { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }
            .getOrElse { JSONObject() }
        if (record.optString("installId").isBlank()) {
            record.put("installId", randomHex(16))
            writeRecord(record)
        }
        return record
    }

    private fun writeRecord(record: JSONObject) {
        prefs.edit().putString(recordKey, record.toString()).apply()
    }

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return value.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value)
    }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun scopeHash(cfg: Config): String =
        sha256Hex(
            listOf(cfg.namespace, cfg.baseUrl.lowercase(Locale.ROOT), cfg.appVersion, cfg.platform)
                .joinToString("\n").toByteArray(Charsets.UTF_8),
        ).take(32)

    companion object {
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        /**
         * Validates the callback state and completes the matching provider grant.
         * Called by the dedicated Android callback activity on an IO dispatcher.
         */
        fun completePendingCallback(context: Context, uri: Uri): String? {
            if (!uri.scheme.equals("mornye", true) || uri.host != "session-grant") return null
            val state = uri.getQueryParameter("state").orEmpty()
            val grant = (uri.getQueryParameter("grant") ?: uri.getQueryParameter("code")).orEmpty()
            if (state.isBlank() || grant.isBlank()) {
                throw IllegalArgumentException("verification callback is missing state or grant")
            }
            val packages = MornyePackageManager(context).listInstalled()
            for (installed in packages) {
                if (installed.manifest.raw.optJSONObject("signedSession") == null) continue
                val host = MornyeSessionHost(
                    context,
                    installed.manifest.name,
                    installed.manifest.raw,
                    installed.manifest.networkDomains,
                )
                val pendingState = host.readRecord().optString("pendingState")
                if (pendingState != state) continue
                host.completeGrant(grant, state)
                return installed.manifest.displayName
            }
            throw IllegalArgumentException("no matching BitChord verification request was found")
        }
    }
}
