package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Permission-gated HTTP host surface for Mornye extensions.
 *
 * Each redirect is checked against the manifest's network allowlist. Local,
 * loopback, link-local and literal-IP destinations are rejected to prevent an
 * extension from using the host as a path into private network services.
 */
internal class MornyeHttpHost(
    private val allowedDomains: List<String>,
    private val cookieJar: MornyeCookieJar? = null,
) {
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar ?: CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun install(quickJs: QuickJs) {
        quickJs.define("__mornye_http") {
            function("request", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val url = args.getOrNull(0)?.toString()
                        ?: throw IllegalArgumentException("http.request requires a URL")
                    val method = args.getOrNull(1)?.toString()?.uppercase(Locale.ROOT) ?: "GET"
                    val headers = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString()
                    return execute(url, method, headers, body).toString()
                }
            })
            function("clearCookies", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    cookieJar?.clear()
                    return "true"
                }
            })
        }
    }

    private fun execute(rawUrl: String, method: String, headersJson: String, body: String?): JSONObject {
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "HTTP method is not supported"
        }
        val headers = JSONObject(headersJson)
        var url = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid HTTP URL")
        var currentMethod = method
        var currentBody = body
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            assertAllowed(url)
            val builder = Request.Builder().url(url)
            headers.keys().forEach { key -> builder.header(key, headers.optString(key)) }
            val requestBody = if (currentMethod in BODY_METHODS) {
                (currentBody ?: "").toRequestBody(
                    headers.optString("Content-Type", "application/json; charset=utf-8").toMediaType()
                )
            } else null
            builder.method(currentMethod, requestBody)
            client.newCall(builder.build()).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) throw IllegalStateException("Too many HTTP redirects")
                    val location = response.header("Location")
                        ?: throw IllegalStateException("Redirect response did not include a location")
                    url = url.resolve(location) ?: throw IllegalStateException("Invalid redirect URL")
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && currentMethod == "POST")) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    return@use
                }
                val bytes = response.body?.source()?.readByteArray(MAX_RESPONSE_BYTES + 1L) ?: ByteArray(0)
                require(bytes.size <= MAX_RESPONSE_BYTES) { "HTTP response exceeded the 4 MB limit" }
                val responseHeaders = JSONObject()
                response.headers.names().forEach { name ->
                    val values = JSONArray()
                    response.headers.values(name).forEach(values::put)
                    responseHeaders.put(name, if (values.length() == 1) values.getString(0) else values)
                }
                return JSONObject()
                    .put("statusCode", response.code)
                    .put("status", response.code)
                    .put("ok", response.isSuccessful)
                    .put("body", bytes.toString(Charsets.UTF_8))
                    .put("headers", responseHeaders)
            }
        }
        throw IllegalStateException("HTTP redirect handling failed")
    }

    private fun assertAllowed(url: okhttp3.HttpUrl) {
        require(url.scheme == "https" || url.scheme == "http") { "Only HTTP and HTTPS are supported" }
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        require(host.isNotBlank() && host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "Local network destinations are blocked"
        }
        require(!host.matches(Regex("^[0-9.]+$")) && !host.contains(':')) {
            "IP-literal destinations are blocked"
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            throw IllegalArgumentException("Could not resolve network host")
        }
        require(addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }) { "Private network destinations are blocked" }
        require(allowedDomains.any { pattern ->
            val allowed = pattern.lowercase(Locale.ROOT)
            if (allowed.startsWith("*.")) {
                val suffix = allowed.removePrefix("*")
                host.endsWith(suffix) && host != suffix.removePrefix(".")
            } else host == allowed
        }) { "Network permission denied for domain $host" }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
