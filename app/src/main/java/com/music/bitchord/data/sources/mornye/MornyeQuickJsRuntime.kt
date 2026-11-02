package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Native side of the Mornye guest contract.
 *
 * The bridge receives the host method and its JSON argument array. Implementors
 * must enforce manifest permissions before touching the network, storage or
 * filesystem; the JavaScript sandbox itself is not a permission boundary.
 */
fun interface MornyeHostBridge {
    fun call(method: String, argumentsJson: String): String
}

/**
 * Dedicated, persistent QuickJS runtime for a single Mornye extension.
 *
 * It deliberately does not reuse QuickJsExecutor's Spine export convention:
 * Mornye code registers an object with registerExtension(), and its methods
 * are invoked through that object. Calls are serialized because one QuickJS
 * interpreter must not be entered concurrently.
 */
class MornyeQuickJsRuntime private constructor(
    private val engine: QuickJs,
    val methods: List<String>,
) : AutoCloseable {
    private val callLock = Mutex()
    @Volatile private var closed = false

    suspend fun invoke(
        method: String,
        argumentsJson: String = "[]",
    ): String = withContext(Dispatchers.Default) {
        require(method.matches(Regex("^[A-Za-z][A-Za-z0-9_]{0,79}$"))) { "Invalid extension method" }
        callLock.withLock {
            check(!closed) { "Extension runtime is closed" }
            // Evaluate the call first, then read the settled value. This is the
            // same two-step Promise pattern used by BitChord's Spine executor.
            val methodLiteral = JsonPrimitive(method).toString()
            val argsLiteral = JsonPrimitive(argumentsJson).toString()
            engine.evaluate<String>(
                """
                globalThis.__mornyeResult = undefined;
                (async function() {
                    try {
                        var value = await globalThis.__mornyeRuntime.invoke($methodLiteral, $argsLiteral);
                        globalThis.__mornyeResult = JSON.stringify(value === undefined ? null : value);
                    } catch (error) {
                        globalThis.__mornyeResult = JSON.stringify({ error: error && error.message ? error.message : String(error) });
                    }
                })();
                'started'
                """.trimIndent(),
            )
            engine.evaluate<String>("globalThis.__mornyeResult") ?: "null"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { engine.close() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun start(
            extensionCode: String,
            configJson: String = "{}",
            host: MornyeHostBridge,
        ): MornyeQuickJsRuntime = withContext(Dispatchers.Default) {
            require(extensionCode.isNotBlank()) { "Extension script is empty" }
            json.parseToJsonElement(configJson)
            val engine = QuickJs.create(Dispatchers.Default)
            try {
                engine.maxStackSize = 512 * 1024L
                engine.define("__mornyeNative") {
                    function("call", object : FunctionBinding<String> {
                        override fun invoke(args: Array<Any?>): String {
                            val method = args.getOrNull(0)?.toString()
                                ?: throw IllegalArgumentException("Host method is missing")
                            val arguments = args.getOrNull(1)?.toString() ?: "[]"
                            return host.call(method, arguments)
                        }
                    })
                }
                engine.evaluate<Unit>(
                    "globalThis.__mornyeConfig = JSON.parse(${JsonPrimitive(configJson)})",
                )
                engine.evaluate<Unit>(BOOTSTRAP)
                engine.evaluate<Unit>(extensionCode)

                val registered = engine.evaluate<Boolean>(
                    "globalThis.__mornyeRuntime.registered()",
                )
                if (!registered) {
                    throw MornyePackageException("Extension script did not call registerExtension()")
                }
                val methodsJson = engine.evaluate<String>(
                    "JSON.stringify(globalThis.__mornyeRuntime.methods())",
                )
                val methods = json.decodeFromString<List<String>>(methodsJson)
                if (methods.isEmpty()) {
                    throw MornyePackageException("Extension registered no callable methods")
                }
                MornyeQuickJsRuntime(engine, methods)
            } catch (error: Throwable) {
                runCatching { engine.close() }
                throw error
            }
        }

        /**
         * Compact bootstrap for the host surface shared by Mornye extensions.
         * Native methods remain behind one bridge so the Android side can
         * enforce each package's declared permissions consistently.
         */
        private val BOOTSTRAP = """
            (function() {
              'use strict';
              var extension = null;
              var config = globalThis.__mornyeConfig || {};
              globalThis.settings = config.settings || {};
              globalThis.registerExtension = function(value) {
                if (!value || typeof value !== 'object') throw new Error('Invalid extension registration');
                extension = value;
                globalThis.extension = value;
              };
              function call(method, args) {
                var raw = __mornyeNative.call(method, JSON.stringify(args || []));
                var result = JSON.parse(raw || '{}');
                if (result && result.failure) throw new Error(String(result.failure));
                return result && Object.prototype.hasOwnProperty.call(result, 'value') ? result.value : result;
              }
              globalThis.log = {};
              ['debug','info','warn','error'].forEach(function(level) {
                log[level] = function() {
                  call('log', [level, Array.prototype.slice.call(arguments).map(String).slice(0, 8)]);
                };
              });
              globalThis.console = {log:log.info, info:log.info, warn:log.warn, error:log.error};
              globalThis.http = {
                get:function(url, headers) { return call('http.request', [String(url), {method:'GET', headers:headers || {}}]); },
                post:function(url, body, headers) { return call('http.request', [String(url), {method:'POST', body:body, headers:headers || {}}]); },
                put:function(url, body, headers) { return call('http.request', [String(url), {method:'PUT', body:body, headers:headers || {}}]); },
                delete:function(url, headers) { return call('http.request', [String(url), {method:'DELETE', headers:headers || {}}]); },
                request:function(url, options) { options = options || {}; return call('http.request', [String(url), options]); }
              };
              globalThis.fetch = async function(url, options) {
                var response = http.request(url, options || {});
                return {
                  ok: !!response.ok,
                  status: Number(response.status || 0),
                  statusText: response.statusText || '',
                  headers: {get:function(name) {
                    var headers = response.headers || {};
                    var key = Object.keys(headers).find(function(k) { return k.toLowerCase() === String(name).toLowerCase(); });
                    return key ? headers[key] : null;
                  }},
                  text: async function() { return response.body || ''; },
                  json: async function() { return JSON.parse(response.body || 'null'); }
                };
              };
              globalThis.storage = {};
              ['get','set','remove','clear'].forEach(function(name) {
                storage[name] = function() { return call('storage.' + name, Array.prototype.slice.call(arguments)); };
              });
              globalThis.credentials = {};
              ['get','store','remove','has'].forEach(function(name) {
                credentials[name] = function() { return call('credentials.' + name, Array.prototype.slice.call(arguments)); };
              });
              globalThis.file = {};
              ['exists','read','write','copy','move','getSize','readBytes','writeBytes','download','downloadSegments'].forEach(function(name) {
                file[name] = function() { return call('file.' + name, Array.prototype.slice.call(arguments)); };
              });
              file.delete = function() { return call('file.delete', Array.prototype.slice.call(arguments)); };
              globalThis.auth = {};
              ['openAuthUrl','getAuthCode','setAuthCode','getTokens','isAuthenticated','clearAuth','getPKCE','startOAuthWithPKCE','exchangeCodeWithPKCE'].forEach(function(name) {
                auth[name] = function() { return call('auth.' + name, Array.prototype.slice.call(arguments)); };
              });
              globalThis.matching = {
                compareStrings:function(a,b) { return call('matching.compareStrings', [String(a),String(b)]); },
                normalizeString:function(value) { return call('matching.normalizeString', [String(value)]); },
                compareDuration:function(a,b,tolerance) { return Math.abs(Number(a)-Number(b)) <= Number(tolerance || 3000); }
              };
              globalThis.utils = {};
              ['randomUserAgent','appVersion','appUserAgent','getAudioQuality','getLyricsLRC','base64Encode','base64Decode','md5','sha256','hmacSHA256','hmacSHA1','encrypt','decrypt','generateKey','sleep'].forEach(function(name) {
                utils[name] = function() { return call('utils.' + name, Array.prototype.slice.call(arguments)); };
              });
              globalThis.__mornyeRuntime = {
                registered:function() { return !!extension; },
                methods:function() {
                  return Object.keys(extension || {}).filter(function(key) { return typeof extension[key] === 'function'; });
                },
                invoke:async function(name, argsJson) {
                  var args = JSON.parse(argsJson || '[]');
                  var method = name;
                  if (method === 'getPlaylist' && !extension.getPlaylist) method = 'getAlbum';
                  if (method === 'handleUrl' && !extension.handleUrl && extension.handleURL) method = 'handleURL';
                  var fn = extension[method] || globalThis[method];
                  if (typeof fn !== 'function') throw new Error('Extension method not found: ' + method);
                  return await fn.apply(extension, args);
                }
              };
            })();
        """.trimIndent()
    }
}
