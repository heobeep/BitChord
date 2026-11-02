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
    val isClosed: Boolean get() = closed

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
              var callbacks = new Map();
              var nextCallback = 0;
              function pack(value, seen) {
                if (value == null) return null;
                if (typeof value === 'function') {
                  var id = ++nextCallback;
                  callbacks.set(id, value);
                  return {__callback:id};
                }
                if (value instanceof ArrayBuffer) {
                  return {__bytes:Array.from(new Uint8Array(value)), __buffer:true};
                }
                if (ArrayBuffer.isView(value)) {
                  return {__bytes:Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))};
                }
                if (typeof value !== 'object') return value;
                seen = seen || [];
                if (seen.length > 80 || seen.indexOf(value) >= 0) throw new Error('Cyclic or deeply nested value');
                if (Array.isArray(value)) return value.map(function(v) { return pack(v, seen.concat([value])); });
                var out = {};
                Object.keys(value).forEach(function(k) { out[k] = pack(value[k], seen.concat([value])); });
                return out;
              }
              function unpack(value) {
                if (!value || typeof value !== 'object') return value;
                if (value.__bytes) {
                  var bytes = new Uint8Array(value.__bytes);
                  return value.__buffer ? bytes.buffer : bytes;
                }
                if (value.__callback) {
                  return function() {
                    return call('callback', [value.__callback, Array.prototype.slice.call(arguments)]);
                  };
                }
                if (Array.isArray(value)) return value.map(unpack);
                var out = {};
                Object.keys(value).forEach(function(k) {
                  Object.defineProperty(out, k, {value:unpack(value[k]), writable:true, enumerable:true, configurable:true});
                });
                return out;
              }
              function call(method, args) {
                var raw = __mornyeNative.call(method, JSON.stringify(pack(args || [])));
                var result = JSON.parse(raw || '{}');
                if (result && result.failure) throw new Error(String(result.failure));
                return unpack(result && Object.prototype.hasOwnProperty.call(result, 'value') ? result.value : result);
              }
              globalThis.registerExtension = function(value) {
                if (!value || typeof value !== 'object') throw new Error('Invalid extension registration');
                extension = value;
                globalThis.extension = value;
              };
              globalThis.settings = config.settings || {};
              globalThis.log = {};
              ['debug','info','warn','error'].forEach(function(level) {
                log[level] = function() {
                  call('log', [level, Array.prototype.slice.call(arguments).map(function(v) {
                    return typeof v === 'object' ? '[object]' : String(v);
                  }).slice(0, 8)]);
                };
              });
              globalThis.console = {log:log.info, info:log.info, warn:log.warn, error:log.error};
              globalThis.http = {
                get:function(url, headers) { return call('http.request', [String(url), {method:'GET', headers:headers || {}}]); },
                delete:function(url, headers) { return call('http.request', [String(url), {method:'DELETE', headers:headers || {}}]); },
                post:function(url, body, headers) { return call('http.request', [String(url), {method:'POST', body:body, headers:headers || {}}]); },
                put:function(url, body, headers) { return call('http.request', [String(url), {method:'PUT', body:body, headers:headers || {}}]); },
                patch:function(url, body, headers) { return call('http.request', [String(url), {method:'PATCH', body:body, headers:headers || {}}]); },
                request:function(url, options) { options = options || {}; return call('http.request', [String(url), options]); },
                clearCookies:function() { return call('http.clearCookies', []); }
              };
              globalThis.fetch = async function(url, options) {
                options = options || {};
                var response = http.request(url, options);
                return {
                  ok: !!response.ok,
                  status: Number(response.status || 0),
                  statusText: response.statusText || '',
                  url: response.url || String(url),
                  headers: {get:function(name) {
                    var headers = response.headers || {};
                    var key = Object.keys(headers).find(function(k) { return k.toLowerCase() === String(name).toLowerCase(); });
                    return key ? headers[key] : null;
                  }},
                  text: async function() { return response.body || ''; },
                  json: async function() { return JSON.parse(response.body || 'null'); },
                  arrayBuffer: async function() { return new Uint8Array(call('decodeBase64Bytes', [response.binary || ''])).buffer; }
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
              ['exists','read','write','delete','copy','move','getSize','readBytes','writeBytes','download','downloadSegments','transformPatternedBlocks'].forEach(function(name) {
                file[name] = function() { return call('file.' + name, Array.prototype.slice.call(arguments)); };
              });
              globalThis.auth = {};
              ['openAuthUrl','getAuthCode','setAuthCode','getTokens','isAuthenticated','clearAuth','getPKCE','startOAuthWithPKCE','exchangeCodeWithPKCE'].forEach(function(name) {
                auth[name] = function() { return call('auth.' + name, Array.prototype.slice.call(arguments)); };
              });
              if (config.signedSession) {
                globalThis.session = {};
                ['status','clear','completeGrant'].forEach(function(name) {
                  session[name] = function() { return call('session.' + name, Array.prototype.slice.call(arguments)); };
                });
                session.signedFetch = function() { return call('session.signedFetch', Array.prototype.slice.call(arguments)); };
              }
              globalThis.matching = {
                compareStrings:function(a,b) { return call('matching.compareStrings', [String(a),String(b)]); },
                compareDuration:function(a,b,tolerance) { return Math.abs(Number(a)-Number(b)) <= Number(tolerance || 3000); },
                normalizeString:function(value) { return call('matching.normalizeString', [String(value)]); }
              };
              globalThis.utils = {};
              [
                'isDownloadCancelled','isRequestCancelled','randomUserAgent','appVersion','appUserAgent',
                'setDownloadStatus','getResolutionRemainingMs','encrypt','decrypt','generateKey',
                'encryptBlockCipher','decryptBlockCipher','decryptCTRSegments','base64Encode',
                'base64Decode','md5','sha256','hmacSHA256','hmacSHA256Base64','hmacSHA1',
                'sleep','getAudioQuality','getLyricsLRC'
              ].forEach(function(name) {
                utils[name] = function() { return call('utils.' + name, Array.prototype.slice.call(arguments)); };
              });
              utils.parseJSON = function(value) {
                try { return JSON.parse(String(value)); } catch (_) { return undefined; }
              };
              utils.stringifyJSON = function(value) {
                try { return JSON.stringify(value); } catch (_) { return ''; }
              };
              globalThis.TextEncoder = function() {
                this.encoding = 'utf-8';
                this.encode = function(value) {
                  var raw = unescape(encodeURIComponent(String(value == null ? '' : value)));
                  var out = new Uint8Array(raw.length);
                  for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
                  return out;
                };
                this.encodeInto = function(value, target) {
                  var bytes = this.encode(value);
                  var count = Math.min(bytes.length, target.length);
                  target.set(bytes.subarray(0, count));
                  return {read:String(value).length, written:count};
                };
              };
              globalThis.TextDecoder = function(encoding) {
                this.encoding = encoding || 'utf-8';
                this.decode = function(value) {
                  if (typeof value === 'string') return value;
                  var bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value || []);
                  var binary = '';
                  for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                  try { return decodeURIComponent(escape(binary)); } catch (_) { return binary; }
                };
              };
              globalThis.btoa = function(value) { return utils.base64Encode(String(value)); };
              globalThis.atob = function(value) { return utils.base64Decode(String(value)); };
              globalThis.URLSearchParams = function(init, onChange) {
                var entries = call('url.query', [init == null ? '' : init]);
                var changed = function() { if (onChange) onChange(call('url.encode', [entries])); };
                this.get = function(k) {
                  var row = entries.find(function(e) { return String(e[0]) === String(k); });
                  return row ? String(row[1]) : null;
                };
                this.getAll = function(k) {
                  return entries.filter(function(e) { return String(e[0]) === String(k); }).map(function(e) { return String(e[1]); });
                };
                this.has = function(k) { return entries.some(function(e) { return String(e[0]) === String(k); }); };
                this.append = function(k,v) { entries.push([String(k),String(v)]); changed(); };
                this.delete = function(k) {
                  for (var i = entries.length - 1; i >= 0; i--) if (String(entries[i][0]) === String(k)) entries.splice(i,1);
                  changed();
                };
                this.set = function(k,v) {
                  this.delete(k);
                  entries.push([String(k),String(v)]);
                  changed();
                };
                this.toString = function() { return call('url.encode', [entries]); };
              };
              globalThis.URL = function(input, base) {
                var parsed = call('url.parse', [String(input), base == null ? null : String(base)]);
                Object.assign(this, parsed);
                this.searchParams = new URLSearchParams(parsed.search, function(encoded) {
                  var hash = parsed.hash || '';
                  parsed.search = encoded ? '?' + encoded : '';
                  parsed.href = parsed.origin + parsed.pathname + parsed.search + hash;
                  this.search = parsed.search;
                  this.href = parsed.href;
                });
                this.toString = this.toJSON = function() { return parsed.href; };
              };
              globalThis.setTimeout = function(fn, ms) {
                call('utils.sleep', [Math.max(0, Math.min(2000, Number(ms) || 0))]);
                if (typeof fn === 'function') fn();
                return 0;
              };
              globalThis.clearTimeout = function() {};
              globalThis.gobackend = {
                sanitizeFilename:function(value) {
                  return String(value || '').replace(/[<>:"/\\|?*\\x00-\\x1f]/g, '_').slice(0,180);
                },
                buildFilename:function(value) { return this.sanitizeFilename(value); },
                getLocalTime:function() { return new Date().toISOString(); },
                getAudioQuality:function() { return utils.getAudioQuality(); },
                getLyricsLRC:function() { return utils.getLyricsLRC.apply(null, arguments); }
              };
              globalThis.Buffer = globalThis.Buffer || {
                from:function(value, encoding) {
                  var bytes;
                  if (typeof value === 'string' && encoding === 'base64') {
                    var decoded = atob(value);
                    bytes = new Uint8Array(decoded.length);
                    for (var i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
                  } else if (typeof value === 'string') {
                    bytes = new TextEncoder().encode(value);
                  } else {
                    bytes = new Uint8Array(value || []);
                  }
                  bytes.toString = function(kind) {
                    if (kind === 'base64') {
                      var text = '';
                      for (var i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
                      return btoa(text);
                    }
                    if (kind === 'hex') return Array.from(bytes).map(function(v) { return v.toString(16).padStart(2,'0'); }).join('');
                    return new TextDecoder().decode(bytes);
                  };
                  return bytes;
                },
                isBuffer:function(value) { return value instanceof Uint8Array; }
              };
              globalThis.__mornyeRuntime = {
                registered:function() { return !!extension; },
                methods:function() {
                  return Object.keys(extension || {}).filter(function(key) { return typeof extension[key] === 'function'; });
                },
                callback:function(id,args) {
                  var callback = callbacks.get(id);
                  if (callback) return callback.apply(null, args || []);
                  throw new Error('Extension callback is unavailable');
                },
                invoke:async function(name, argsJson) {
                  var args = unpack(JSON.parse(argsJson || '[]'));
                  var method = name;
                  if (method === 'getPlaylist' && !extension.getPlaylist) method = 'getAlbum';
                  if (method === 'handleUrl' && !extension.handleUrl && extension.handleURL) method = 'handleURL';
                  if (method === 'download') args.splice(3, 0, function() {});
                  if (method === 'postProcessV2' && !extension.postProcessV2) {
                    method = 'postProcess';
                    if (args[0] && args[0].path) args[0] = args[0].path;
                  }
                  var fn = extension[method] || globalThis[method];
                  if (typeof fn !== 'function') throw new Error('Extension method not found: ' + method);
                  return await fn.apply(extension, args);
                }
              };
            })();
        
        """.trimIndent()
    }
}
