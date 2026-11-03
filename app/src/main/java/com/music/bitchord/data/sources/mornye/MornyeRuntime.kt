package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Isolated QuickJS runtime for one installed Mornye extension. */
class MornyeRuntime private constructor(
    private val quickJs: QuickJs,
    val extensionId: String,
    val extensionName: String,
) {
    private val callMutex = Mutex()
    private var closed = false

    suspend fun initialize(configJson: String = "{}"): String? =
        invoke("initialize", configJson)?.let(::unwrapJsonString)

    /** Calls the optional download-provider URL resolver. */
    suspend fun getDownloadUrl(trackId: String, quality: String): JSONObject {
        val raw = invoke("getDownloadUrl", "${JSONObject.quote(trackId)},${JSONObject.quote(quality)}")
            ?: throw IllegalStateException("getDownloadUrl returned no result")
        return runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalStateException("getDownloadUrl returned invalid JSON: ${it.message}") }
    }

    suspend fun searchTracks(query: String, limit: Int = 20): JSONArray {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val raw = invoke("searchTracks", "${JSONObject.quote(query)},$limit")
            ?: throw IllegalStateException("searchTracks returned no result")
        return try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Mornye searchTracks must return an array: ${error.message}")
        }
    }

    suspend fun close() {
        callMutex.withLock {
            if (closed) return
            runCatching { invokeLocked("cleanup", "") }
            closed = true
            withContext(Dispatchers.Default) { quickJs.close() }
        }
    }

    private suspend fun invoke(name: String, arguments: String): String? =
        callMutex.withLock {
            check(!closed) { "Mornye runtime is closed" }
            invokeLocked(name, arguments)
        }

    private suspend fun invokeLocked(name: String, arguments: String): String? =
        withContext(Dispatchers.Default) {
            val safeName = JSONObject.quote(name)
            quickJs.evaluate<String>(
                """
                var __mornye_call_result = undefined;
                (async function() {
                    try {
                        var extension = __mornye_registered_extension;
                        var fn = extension && extension[$safeName];
                        if (typeof fn !== 'function') {
                            __mornye_call_result = JSON.stringify(null);
                            return;
                        }
                        var value = await fn.apply(extension, [$arguments]);
                        __mornye_call_result = JSON.stringify(value === undefined ? null : value);
                    } catch (error) {
                        __mornye_call_result = JSON.stringify({
                            __mornye_error: error && error.message ? error.message : String(error)
                        });
                    }
                })();
                'started'
                """.trimIndent()
            )
            val raw = quickJs.evaluate<String>("__mornye_call_result")
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            if (parsed?.has("__mornye_error") == true) {
                throw IllegalStateException("Mornye $name failed: ${parsed.optString("__mornye_error")}")
            }
            raw.takeUnless { it == "null" }
        }

    private fun unwrapJsonString(raw: String): String? =
        runCatching { org.json.JSONTokener(raw).nextValue() }
            .getOrNull()
            ?.toString()

    companion object {
        private val BOOTSTRAP = """
            var __mornye_registered_extension = null;
            function registerExtension(extension) {
                if (!extension || typeof extension !== 'object') {
                    throw new Error('registerExtension expects an object');
                }
                if (__mornye_registered_extension !== null) {
                    throw new Error('registerExtension may only be called once');
                }
                __mornye_registered_extension = extension;
            }
            var console = {
                log: function() {}, info: function() {},
                warn: function() {}, error: function() {},
                debug: function() {}
            };
            var log = console;
            var utils = {
                parseJSON: function(value) { return JSON.parse(value); },
                stringifyJSON: function(value) { return JSON.stringify(value); }
            };
            var gobackend = {
                sanitizeFilename: function(value) {
                    return String(value).replaceAll('/', '_').replace(/[\\:*?"<>|]/g, '_').trim();
                }
            };
            function __mornyeRequest(url, method, body, headers) {
                var raw = __mornye_http.request(
                    String(url), method || 'GET',
                    JSON.stringify(headers || {}),
                    body == null ? null : (typeof body === 'string' ? body : JSON.stringify(body))
                );
                return JSON.parse(raw);
            }
            var storage = {
                get: function(key) { return JSON.parse(__mornye_storage.get(String(key))); },
                set: function(key, value) { __mornye_storage.set(String(key), JSON.stringify(value)); },
                remove: function(key) { __mornye_storage.remove(String(key)); },
                clear: function() { __mornye_storage.clear(); }
            };
            var file = {
                exists: function(path) { return __mornye_file.exists(String(path)) === 'true'; },
                read: function(path) { return __mornye_file.read(String(path)); },
                write: function(path, content) { return __mornye_file.write(String(path), String(content)); },
                delete: function(path) { return __mornye_file.delete(String(path)) === 'true'; }
            };
            function __mornyeHttpOptions(url, method, options) {
                options = options || {};
                if (options.params) {
                    var pairs = [];
                    Object.keys(options.params).forEach(function(key) {
                        var value = options.params[key];
                        if (value !== undefined && value !== null) {
                            pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
                        }
                    });
                    if (pairs.length) url += (String(url).indexOf('?') >= 0 ? '&' : '?') + pairs.join('&');
                }
                var headers = options.headers || {};
                if (!options.headers) {
                    Object.keys(options).forEach(function(key) {
                        if (key !== 'params' && key !== 'method' && key !== 'body') headers[key] = options[key];
                    });
                }
                return __mornyeRequest(url, method || options.method || 'GET', options.body, headers);
            }
            var http = {
                request: function(url, options) {
                    return __mornyeHttpOptions(url, null, options);
                },
                get: function(url, options) { return __mornyeHttpOptions(url, 'GET', options); },
                post: function(url, body, headers) { return __mornyeRequest(url, 'POST', body, headers); },
                put: function(url, body, headers) { return __mornyeRequest(url, 'PUT', body, headers); },
                patch: function(url, body, headers) { return __mornyeRequest(url, 'PATCH', body, headers); },
                delete: function(url, headers) { return __mornyeRequest(url, 'DELETE', null, headers); },
                clearCookies: function() { __mornye_http.clearCookies(); }
            };
            var fetch = function(url, options) {
                var response = __mornyeRequest(
                    url, options && options.method || 'GET',
                    options && options.body, options && options.headers
                );
                return {
                    ok: response.ok, status: response.status, statusText: '',
                    url: String(url), headers: response.headers,
                    text: function() { return response.body; },
                    json: function() { return JSON.parse(response.body); }
                };
            };
            // Signed-session calls are performed by the native host so session
            // secrets never enter extension JavaScript or ordinary storage.
            var session = {
                enabled: typeof __mornye_session !== 'undefined' &&
                    __mornye_session.enabled() === 'true',
                status: function() {
                    if (typeof __mornye_session === 'undefined') {
                        return { enabled: false, authenticated: false };
                    }
                    return JSON.parse(__mornye_session.call('status', '[]'));
                },
                clear: function() {
                    if (typeof __mornye_session === 'undefined') {
                        return { success: false, error: 'signed sessions are not configured' };
                    }
                    return JSON.parse(__mornye_session.call('clear', '[]'));
                },
                completeGrant: function(grant) {
                    if (typeof __mornye_session === 'undefined') {
                        return { success: false, error: 'signed sessions are not configured' };
                    }
                    return JSON.parse(__mornye_session.call(
                        'completeGrant', JSON.stringify([String(grant || '')])
                    ));
                },
                signedFetch: function(method, path, body, headers) {
                    if (typeof __mornye_session === 'undefined') {
                        return { ok: false, error: 'signed sessions are not configured' };
                    }
                    var payload = body == null ? null :
                        (typeof body === 'string' ? body : JSON.stringify(body));
                    return JSON.parse(__mornye_session.call('signedFetch', JSON.stringify([
                        String(method || 'GET'), String(path || ''), payload, headers || {}
                    ])));
                }
            };
        """.trimIndent()

        suspend fun load(
            extension: MornyePackageManager.InstalledExtension,
            context: Context? = null,
        ): MornyeRuntime =
            load(
                extensionId = extension.manifest.name,
                extensionName = extension.manifest.displayName,
                javascript = extension.script.readText(Charsets.UTF_8),
                allowedDomains = extension.manifest.networkDomains,
                storageFile = java.io.File(extension.dataDirectory, "storage.json"),
                storagePermission = extension.manifest.storagePermission,
                fileDirectory = extension.dataDirectory,
                filePermission = extension.manifest.filePermission,
                context = context,
                manifest = extension.manifest.raw,
            )

        suspend fun load(
            extensionId: String,
            extensionName: String,
            javascript: String,
            allowedDomains: List<String> = emptyList(),
            storageFile: java.io.File? = null,
            storagePermission: Boolean = false,
            fileDirectory: java.io.File? = null,
            filePermission: Boolean = false,
            context: Context? = null,
            manifest: JSONObject? = null,
        ): MornyeRuntime = withContext(Dispatchers.Default) {
            require(extensionId.isNotBlank()) { "extensionId must not be blank" }
            require(extensionName.isNotBlank()) { "extensionName must not be blank" }
            require(javascript.isNotBlank()) { "extension script must not be blank" }

            val engine = QuickJs.create(Dispatchers.Default)
            engine.maxStackSize = 512 * 1024L
            try {
                val cookieJar = context?.let { MornyeCookieJar(it, extensionId) }
                MornyeHttpHost(allowedDomains, cookieJar).install(engine)
                if (context != null && manifest != null) {
                    MornyeSessionHost(context, extensionId, manifest, allowedDomains).install(engine)
                }
                MornyeStorageHost(storageFile ?: java.io.File("mornye-storage-disabled"), storagePermission).install(engine)
                MornyeFileHost(fileDirectory ?: java.io.File("mornye-files-disabled"), filePermission).install(engine)
                engine.evaluate<String>(BOOTSTRAP + "\n" + javascript + "\n'loaded'")
                val registered = engine.evaluate<String>(
                    "typeof __mornye_registered_extension === 'object' && __mornye_registered_extension !== null"
                )
                check(registered == "true") {
                    "Extension did not call registerExtension()"
                }
                MornyeRuntime(engine, extensionId, extensionName)
            } catch (error: Throwable) {
                engine.close()
                throw error
            }
        }
    }
}
