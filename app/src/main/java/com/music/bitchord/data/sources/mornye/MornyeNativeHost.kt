package com.music.bitchord.data.sources.mornye

import android.content.Context
import android.util.Base64
import android.util.Log
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Per-extension Android host. All network, storage and filesystem access is
 * checked here; JavaScript globals are only a convenience layer, not a trust
 * boundary. Authentication UI and signed gateway sessions remain explicitly
 * unsupported in this first implementation.
 */
class MornyeNativeHost(
    context: Context,
    private val manifest: MornyeManifest,
    workingDirectory: File,
) : MornyeHostBridge {

    private val appContext = context.applicationContext
    private val root = workingDirectory.canonicalFile.apply { mkdirs() }
    private val domains = manifest.networkPermissions
    private val allowHttp = manifest.raw["permissions"]
        ?.let { runCatching { it.jsonObject["allowHttp"]?.jsonPrimitive?.booleanOrNull }.getOrNull() }
        ?: false

    private val encryptedStore = EncryptedPrefs.open(
        appContext,
        "mornye_" + manifest.name,
        "mornye_" + manifest.name + "_plain",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build()

    override fun call(method: String, argumentsJson: String): String = try {
        val args = JSONArray(argumentsJson)
        val value = dispatch(method, args)
        JSONObject().put("value", value ?: JSONObject.NULL).toString()
    } catch (error: Throwable) {
        JSONObject().put(
            "failure",
            (error.message ?: error.javaClass.simpleName).take(400),
        ).toString()
    }

    private fun dispatch(method: String, args: JSONArray): Any? = when (method) {
        "log" -> log(args)
        "http.request" -> httpRequest(args)
        "http.clearCookies" -> JSONObject().put("success", true)

        "storage.get" -> storageGet(args.optString(0))
        "storage.set" -> storageSet(args.optString(0), args.opt(1))
        "storage.remove" -> storageRemove(args.optString(0))
        "storage.clear" -> storageClear()

        "credentials.get" -> credentialGet(args.optString(0))
        "credentials.set", "credentials.store" -> credentialSet(args.optString(0), args.opt(1))
        "credentials.remove" -> credentialRemove(args.optString(0))
        "credentials.has" -> {
            requireStorage()
            JSONObject().put("found", encryptedStore.contains(credentialKey(args.optString(0))))
        }

        "file.exists" -> resolveFile(args.optString(0)).exists()
        "file.read" -> readText(args.optString(0))
        "file.write" -> writeText(args.optString(0), args.opt(1))
        "file.delete" -> deleteFile(args.optString(0))
        "file.copy" -> copyFile(args.optString(0), args.optString(1), false)
        "file.move" -> copyFile(args.optString(0), args.optString(1), true)
        "file.getSize" -> resolveFile(args.optString(0)).length()
        "file.readBytes" -> readBytes(args.optString(0))
        "file.writeBytes" -> writeBytes(args.optString(0), args.opt(1))
        "file.download" -> download(args.optString(0), args.optString(1), args.optJSONObject(2))
        "file.downloadSegments" -> downloadSegments(args.optJSONArray(0), args.optString(1), args.optJSONObject(2))

        "matching.normalizeString" -> normalize(args.optString(0))
        "matching.compareStrings" -> similarity(args.optString(0), args.optString(1))
        "matching.compareDuration" -> kotlin.math.abs(args.optLong(0) - args.optLong(1)) <= args.optLong(2, 3000)

        "url.parse" -> parseUrl(args.optString(0), args.optString(1).ifBlank { null })
        "url.query" -> queryEntries(args.opt(0))
        "url.encode" -> encodeQuery(args.opt(0))
        "encode" -> JSONArray(args.optString(0).toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff })
        "decode" -> String(
            ByteArray(args.optJSONArray(0)?.length() ?: 0) { args.optJSONArray(0)!!.getInt(it).toByte() },
            Charsets.UTF_8,
        )
        "base64Bytes" -> Base64.encodeToString(
            ByteArray(args.optJSONArray(0)?.length() ?: 0) { args.optJSONArray(0)!!.getInt(it).toByte() },
            Base64.NO_WRAP,
        )
        "decodeBase64Bytes" -> JSONArray(
            Base64.decode(args.optString(0), Base64.DEFAULT).map { it.toInt() and 0xff },
        )

        "utils.randomUserAgent" -> USER_AGENT
        "utils.appVersion" -> com.music.bitchord.BuildConfig.VERSION_NAME
        "utils.appUserAgent" -> "BitChord/" + com.music.bitchord.BuildConfig.VERSION_NAME
        "utils.base64Encode" -> Base64.encodeToString(args.optString(0).toByteArray(), Base64.NO_WRAP)
        "utils.base64Decode" -> String(Base64.decode(args.optString(0), Base64.DEFAULT), Charsets.UTF_8)
        "utils.sha256" -> digest("SHA-256", args.optString(0))
        "utils.md5" -> digest("MD5", args.optString(0))
        "utils.hmacSHA256" -> hmac("HmacSHA256", args.optString(0), args.optString(1))
        "utils.hmacSHA1" -> hmac("HmacSHA1", args.optString(0), args.optString(1))
        "utils.sleep" -> {
            Thread.sleep(args.optLong(0).coerceIn(0, 2_000))
            JSONObject().put("success", true)
        }
        "utils.getAudioQuality" -> JSONObject().put("quality", "HIGH")
        "utils.isDownloadCancelled", "utils.isRequestCancelled" -> false
        "utils.setDownloadStatus" -> JSONObject().put("success", true)

        "auth.openAuthUrl", "auth.getAuthCode", "auth.setAuthCode",
        "auth.getTokens", "auth.isAuthenticated", "auth.clearAuth",
        "auth.getPKCE", "auth.generatePKCE", "auth.startOAuthWithPKCE",
        "auth.exchangeCodeWithPKCE", "session.status", "session.clear",
        "session.completeGrant", "session.signedFetch" ->
            throw UnsupportedOperationException("Authentication and signed sessions are not enabled in this BitChord preview")

        else -> throw UnsupportedOperationException("Unsupported Mornye host method: " + method)
    }

    private fun log(args: JSONArray): JSONObject {
        val level = args.optString(0, "info").uppercase(Locale.ROOT)
        // Deliberately omit extension-supplied message text: it can contain tokens.
        when (level) {
            "ERROR" -> Log.e(TAG, "Mornye extension " + manifest.name + " reported an error")
            "WARN" -> Log.w(TAG, "Mornye extension " + manifest.name + " reported a warning")
            else -> Log.d(TAG, "Mornye extension " + manifest.name + " logged a message")
        }
        return JSONObject().put("success", true)
    }

    private fun requireStorage() {
        if (!manifest.storagePermission) throw SecurityException("Extension storage permission denied")
    }

    private fun storageKey(key: String) = "mornye.storage." + safeKey(key)
    private fun credentialKey(key: String) = "mornye.credential." + safeKey(key)

    private fun safeKey(key: String): String {
        require(key.isNotBlank() && key.length <= 180 && !key.contains('\u0000')) {
            "Invalid extension storage key"
        }
        return key
    }

    private fun storageGet(key: String): JSONObject {
        requireStorage()
        val raw = encryptedStore.getString(storageKey(key), null)
            ?: return JSONObject().put("found", false)
        return JSONObject().put("found", true).put("value", JSONTokener(raw).nextValue())
    }

    private fun storageSet(key: String, value: Any?): JSONObject {
        requireStorage()
        encryptedStore.edit().putString(storageKey(key), jsonString(value)).apply()
        return JSONObject().put("success", true)
    }

    private fun storageRemove(key: String): JSONObject {
        requireStorage()
        encryptedStore.edit().remove(storageKey(key)).apply()
        return JSONObject().put("success", true)
    }

    private fun storageClear(): JSONObject {
        requireStorage()
        val edit = encryptedStore.edit()
        encryptedStore.all.keys.filter { it.startsWith("mornye.storage.") }.forEach(edit::remove)
        edit.apply()
        return JSONObject().put("success", true)
    }

    private fun credentialGet(key: String): JSONObject {
        requireStorage()
        val raw = encryptedStore.getString(credentialKey(key), null)
            ?: return JSONObject().put("found", false)
        return JSONObject().put("found", true).put("value", JSONTokener(raw).nextValue())
    }

    private fun credentialSet(key: String, value: Any?): JSONObject {
        requireStorage()
        encryptedStore.edit().putString(credentialKey(key), jsonString(value)).apply()
        return JSONObject().put("success", true)
    }

    private fun credentialRemove(key: String): JSONObject {
        requireStorage()
        encryptedStore.edit().remove(credentialKey(key)).apply()
        return JSONObject().put("success", true)
    }

    private fun jsonString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun httpRequest(args: JSONArray): JSONObject {
        val options = args.optJSONObject(1) ?: JSONObject()
        val result = request(
            rawUrl = args.optString(0),
            method = options.optString("method", "GET"),
            headers = options.optJSONObject("headers") ?: JSONObject(),
            body = options.opt("body"),
            maxBytes = MAX_HTTP_RESPONSE,
        )
        val binary = result.contentType.startsWith("audio/") ||
            result.contentType.startsWith("image/") ||
            result.contentType == "application/octet-stream"
        return JSONObject()
            .put("status", result.status)
            .put("ok", result.status in 200..299)
            .put("statusText", if (result.status in 200..299) "OK" else "HTTP " + result.status)
            .put("headers", result.headers)
            .put("body", if (binary) "" else String(result.bytes, Charsets.UTF_8))
            .put("binary", if (binary) Base64.encodeToString(result.bytes, Base64.NO_WRAP) else "")
    }

    private data class HttpResult(
        val status: Int,
        val headers: JSONObject,
        val contentType: String,
        val bytes: ByteArray,
    )

    /** Redirects are followed manually so every destination gets re-authorized. */
    private fun request(
        rawUrl: String,
        method: String,
        headers: JSONObject,
        body: Any?,
        maxBytes: Int,
    ): HttpResult {
        var current = rawUrl
        var verb = method.uppercase(Locale.ROOT)
        var payload = body
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val url = validateUrl(current)
            val addresses = publicAddresses(url.host)
            val pinned = client.newBuilder().dns(Dns { host ->
                if (host.equals(url.host, ignoreCase = true)) addresses
                else throw UnknownHostException("Unexpected secondary host in extension request")
            }).build()

            val builder = Request.Builder().url(url)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (name.equals("host", true) || name.equals("content-length", true) ||
                    name.equals("connection", true) || name.equals("transfer-encoding", true)
                ) continue
                builder.header(name, headers.optString(name))
            }
            if (!headers.has("User-Agent")) builder.header("User-Agent", USER_AGENT)

            val text = when (payload) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> payload.toString()
                else -> payload.toString()
            }
            val contentType = headers.optString("Content-Type", "application/json; charset=utf-8")
                .toMediaTypeOrNull()
            val requestBody = text?.toRequestBody(contentType)
            val request = when (verb) {
                "GET" -> builder.get().build()
                "HEAD" -> builder.head().build()
                "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody()).build()
                "PUT" -> builder.put(requestBody ?: ByteArray(0).toRequestBody()).build()
                "PATCH" -> builder.patch(requestBody ?: ByteArray(0).toRequestBody()).build()
                "DELETE" -> if (requestBody == null) builder.delete().build() else builder.delete(requestBody).build()
                else -> throw IllegalArgumentException("Unsupported HTTP method")
            }

            pinned.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (response.code in REDIRECT_CODES && location != null) {
                    if (redirect >= MAX_REDIRECTS) throw IOException("Too many extension HTTP redirects")
                    current = URI(current).resolve(location).toString()
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && verb == "POST")) {
                        verb = "GET"
                        payload = null
                    }
                    return@use
                }
                val responseHeaders = JSONObject()
                response.headers.names().forEach { name ->
                    responseHeaders.put(name, response.header(name).orEmpty())
                }
                val bytes = response.body?.byteStream()?.use { readBounded(it, maxBytes) } ?: ByteArray(0)
                val type = response.header("Content-Type").orEmpty().substringBefore(';').lowercase(Locale.ROOT)
                return HttpResult(response.code, responseHeaders, type, bytes)
            }
            // The next loop iteration re-validates the redirected URL.
        }
        throw IOException("Extension HTTP redirect resolution failed")
    }

    private fun validateUrl(raw: String): okhttp3.HttpUrl {
        val url = okhttp3.HttpUrl.Companion.toHttpUrlOrNull(raw)
            ?: throw IllegalArgumentException("Extension requested an invalid URL")
        if (url.scheme != "https" && !(allowHttp && url.scheme == "http")) {
            throw SecurityException("Extension network permission requires HTTPS")
        }
        val permitted = domains.any { declared ->
            if (declared.startsWith("*.")) {
                val suffix = declared.substring(2)
                !url.host.equals(suffix, true) && url.host.endsWith("." + suffix, true)
            } else {
                url.host.equals(declared, true)
            }
        }
        if (!permitted) throw SecurityException("Extension network permission denied for " + url.host)
        return url
    }

    /** Pin the DNS answer used for the permission check to the actual request. */
    private fun publicAddresses(host: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(host).toList()
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw SecurityException("Extension network destination is not a public address")
        }
        return addresses
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        if (address is Inet6Address) {
            val first = address.address.first().toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return false // IPv6 unique-local fc00::/7
        }
        return true
    }

    private fun download(url: String, destination: String, options: JSONObject?): JSONObject {
        requireFilePermission()
        val target = resolveFile(destination)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".part")
        if (temporary.exists()) temporary.delete()
        val headers = options?.optJSONObject("headers") ?: JSONObject()
        val result = downloadTo(url, options?.optString("method", "GET") ?: "GET", headers, temporary)
        if (result.status !in 200..299) {
            temporary.delete()
            throw IOException("Extension download returned HTTP " + result.status)
        }
        if (target.exists() && !target.delete()) throw IOException("Could not replace extension download")
        if (!temporary.renameTo(target)) throw IOException("Could not finalize extension download")
        return JSONObject().put("success", true).put("path", destination).put("size", target.length())
    }

    private fun downloadSegments(
        segments: JSONArray?,
        destination: String,
        options: JSONObject?,
    ): JSONObject {
        requireFilePermission()
        val values = segments ?: throw IllegalArgumentException("Segment list is missing")
        if (values.length() !in 1..MAX_SEGMENTS) throw IllegalArgumentException("Invalid segment count")
        val target = resolveFile(destination)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".part")
        if (temporary.exists()) temporary.delete()
        var total = 0L
        FileOutputStream(temporary).use { output ->
            for (index in 0 until values.length()) {
                val item = values.opt(index)
                val url = if (item is JSONObject) item.optString("url") else item.toString()
                val headers = if (item is JSONObject) item.optJSONObject("headers")
                    ?: options?.optJSONObject("headers") ?: JSONObject()
                else options?.optJSONObject("headers") ?: JSONObject()
                val result = request(url, "GET", headers, null, MAX_HTTP_RESPONSE)
                if (result.status !in 200..299) throw IOException("Segment request returned HTTP " + result.status)
                total += result.bytes.size
                if (total > MAX_AUDIO_BYTES) throw IOException("Segmented audio exceeds the size limit")
                output.write(result.bytes)
            }
        }
        if (target.exists() && !target.delete()) throw IOException("Could not replace segmented download")
        if (!temporary.renameTo(target)) throw IOException("Could not finalize segmented download")
        return JSONObject().put("success", true).put("path", destination).put("size", total)
    }

    private fun downloadTo(url: String, method: String, headers: JSONObject, destination: File): HttpResult {
        // Download transfers are streamed to disk rather than buffered in heap.
        var current = url
        var verb = method.uppercase(Locale.ROOT)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val parsed = validateUrl(current)
            val addresses = publicAddresses(parsed.host)
            val pinned = client.newBuilder().dns(Dns { host ->
                if (host.equals(parsed.host, true)) addresses
                else throw UnknownHostException("Unexpected secondary host in extension download")
            }).build()
            val builder = Request.Builder().url(parsed)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (!name.equals("host", true) && !name.equals("content-length", true)) {
                    builder.header(name, headers.optString(name))
                }
            }
            if (!headers.has("User-Agent")) builder.header("User-Agent", USER_AGENT)
            val request = when (verb) {
                "HEAD" -> builder.head().build()
                "POST" -> builder.post(ByteArray(0).toRequestBody()).build()
                "PUT" -> builder.put(ByteArray(0).toRequestBody()).build()
                "DELETE" -> builder.delete().build()
                else -> builder.get().build()
            }
            pinned.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (response.code in REDIRECT_CODES && location != null) {
                    if (redirect >= MAX_REDIRECTS) throw IOException("Too many download redirects")
                    current = URI(current).resolve(location).toString()
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && verb == "POST")) verb = "GET"
                    return@use
                }
                var written = 0L
                response.body?.byteStream()?.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > MAX_AUDIO_BYTES) throw IOException("Extension download exceeds the file size limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return HttpResult(
                    response.code,
                    JSONObject(),
                    response.header("Content-Type").orEmpty().substringBefore(';').lowercase(Locale.ROOT),
                    ByteArray(0),
                ).copy(headers = JSONObject().put("downloadedBytes", written))
            }
        }
        throw IOException("Extension download failed")
    }

    private fun requireFilePermission() {
        if (!manifest.filePermission) throw SecurityException("Extension file permission denied")
    }

    private fun resolveFile(raw: String): File {
        requireFilePermission()
        require(raw.isNotBlank() && !raw.contains('\u0000') && !raw.contains('\\')) {
            "Invalid extension file path"
        }
        val parts = raw.replace(File.separatorChar, '/').split('/')
        if (parts.any { it == ".." || it == "." }) {
            throw SecurityException("Extension file path traversal denied")
        }
        val candidate = if (File(raw).isAbsolute) File(raw) else File(root, raw)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = candidate.canonicalFile
        if (canonicalTarget != canonicalRoot &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) throw SecurityException("Extension file path escaped its sandbox")
        var current = canonicalRoot
        for (part in parts.filter { it.isNotEmpty() }) {
            current = File(current, part)
            if (current.exists() && java.nio.file.Files.isSymbolicLink(current.toPath())) {
                throw SecurityException("Extension paths cannot contain symbolic links")
            }
        }
        return canonicalTarget
    }

    private fun readText(path: String): String {
        val file = resolveFile(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) throw IOException("Extension file missing or too large")
        return file.readText(Charsets.UTF_8)
    }

    private fun writeText(path: String, value: Any?): JSONObject {
        val file = resolveFile(path)
        val bytes = (value?.toString() ?: "").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Extension file exceeds the write limit")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return JSONObject().put("success", true)
    }

    private fun deleteFile(path: String): JSONObject {
        val file = resolveFile(path)
        if (file.exists() && !file.deleteRecursively()) throw IOException("Could not delete extension file")
        return JSONObject().put("success", true)
    }

    private fun copyFile(from: String, to: String, move: Boolean): JSONObject {
        val source = resolveFile(from)
        val target = resolveFile(to)
        if (!source.isFile) throw IOException("Extension source file does not exist")
        target.parentFile?.mkdirs()
        if (move) {
            if (target.exists() && !target.delete()) throw IOException("Could not replace extension target")
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                if (!source.delete()) throw IOException("Could not remove moved extension file")
            }
        } else {
            source.copyTo(target, overwrite = true)
        }
        return JSONObject().put("success", true)
    }

    private fun readBytes(path: String): JSONObject {
        val file = resolveFile(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) throw IOException("Extension file missing or too large")
        val array = JSONArray()
        file.readBytes().forEach { array.put(it.toInt() and 0xff) }
        return JSONObject().put("__bytes", array).put("__buffer", true)
    }

    private fun writeBytes(path: String, value: Any?): JSONObject {
        val bytes = when (value) {
            is JSONArray -> ByteArray(value.length()) { value.getInt(it).toByte() }
            is JSONObject -> {
                val array = value.optJSONArray("__bytes") ?: throw IllegalArgumentException("Invalid byte buffer")
                ByteArray(array.length()) { array.getInt(it).toByte() }
            }
            else -> Base64.decode(value?.toString().orEmpty(), Base64.DEFAULT)
        }
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Extension file exceeds the write limit")
        val file = resolveFile(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return JSONObject().put("success", true)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""\s*[(\[](?:feat\.?|ft\.?|with|remaster(?:ed)?|official).*?[)\]]"""), "")
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(left: String, right: String): Double {
        val a = left.lowercase(Locale.ROOT)
        val b = right.lowercase(Locale.ROOT)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length > 512 || b.length > 512) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun parseUrl(input: String, base: String?): JSONObject {
        val uri = if (base == null) URI(input) else URI(base).resolve(input)
        if (!uri.isAbsolute || uri.scheme !in setOf("http", "https")) {
            throw IllegalArgumentException("Invalid HTTP URL")
        }
        return JSONObject()
            .put("href", uri.toString())
            .put("origin", uri.scheme + "://" + uri.rawAuthority)
            .put("protocol", uri.scheme + ":")
            .put("host", uri.rawAuthority ?: "")
            .put("hostname", uri.host ?: "")
            .put("port", if (uri.port < 0) "" else uri.port.toString())
            .put("pathname", uri.rawPath ?: "/")
            .put("search", uri.rawQuery?.let { "?" + it } ?: "")
            .put("hash", uri.rawFragment?.let { "#" + it } ?: "")
            .put("username", uri.rawUserInfo?.substringBefore(':') ?: "")
            .put("password", uri.rawUserInfo?.substringAfter(':', "") ?: "")
    }

    private fun queryEntries(value: Any?): JSONArray {
        val output = JSONArray()
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) {
                val pair = value.optJSONArray(i) ?: continue
                if (pair.length() >= 2) output.put(JSONArray().put(pair.opt(0).toString()).put(pair.opt(1).toString()))
            }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    output.put(JSONArray().put(key).put(value.opt(key).toString()))
                }
            }
            else -> {
                val query = value?.toString().orEmpty().removePrefix("?")
                query.split('&').filter { it.isNotEmpty() }.forEach { item ->
                    val key = item.substringBefore('=')
                    val raw = item.substringAfter('=', "")
                    output.put(JSONArray().put(URLDecoder.decode(key, "UTF-8")).put(URLDecoder.decode(raw, "UTF-8")))
                }
            }
        }
        return output
    }

    private fun encodeQuery(value: Any?): String {
        val entries = queryEntries(value)
        return (0 until entries.length()).joinToString("&") { index ->
            val pair = entries.getJSONArray(index)
            URLEncoder.encode(pair.getString(0), "UTF-8") + "=" +
                URLEncoder.encode(pair.getString(1), "UTF-8")
        }
    }

    private fun digest(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hmac(algorithm: String, key: String, value: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(), algorithm))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Extension HTTP response exceeds its size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "BitChord"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        const val MAX_HTTP_RESPONSE = 16 * 1024 * 1024
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_SEGMENTS = 20_000
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
