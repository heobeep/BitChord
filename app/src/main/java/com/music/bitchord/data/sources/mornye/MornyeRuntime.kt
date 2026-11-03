package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Mornye-compatible JavaScript runtime.
 *
 * This is deliberately a first runtime milestone, not a claim of full Mornye
 * host compatibility: it implements registerExtension(), lifecycle calls and
 * metadata search. Network, storage, credentials and file APIs are added by
 * the permission-aware host bridge in later milestones.
 *
 * One QuickJS context is kept alive for the extension lifetime so module-level
 * state and the extension object's state survive between calls. Calls are
 * serialized because a QuickJS context must never be entered concurrently.
 */
class MornyeRuntime private constructor(
    private val quickJs: QuickJs,
    val extensionId: String,
    val extensionName: String,
) {
    private val callMutex = Mutex()
    private var closed = false

    /** Calls the optional extension initialize(config) hook. */
    suspend fun initialize(configJson: String = "{}"): String? =
        invoke("initialize", configJson)?.let(::unwrapJsonString)

    /**
     * Calls searchTracks(query, limit) and returns its JSON array.
     *
     * The caller is responsible for mapping Mornye's track schema to BitChord's
     * internal Track model; keeping that adapter separate avoids silently
     * dropping Metadata-provider fields during the runtime milestone.
     */
    suspend fun searchTracks(query: String, limit: Int = 20): JSONArray {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val args = "${JSONObject.quote(query)},$limit"
        val raw = invoke("searchTracks", args)
            ?: throw IllegalStateException("searchTracks returned no result")
        return try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Mornye searchTracks must return an array: ${error.message}")
        }
    }

    /** Calls cleanup() if present, then releases the JavaScript engine. */
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
        """.trimIndent()

        /**
         * Creates an isolated QuickJS context, evaluates one extension script,
         * and rejects scripts that do not call registerExtension().
         */
        suspend fun load(
            extensionId: String,
            extensionName: String,
            javascript: String,
        ): MornyeRuntime = withContext(Dispatchers.Default) {
            require(extensionId.isNotBlank()) { "extensionId must not be blank" }
            require(extensionName.isNotBlank()) { "extensionName must not be blank" }
            require(javascript.isNotBlank()) { "extension script must not be blank" }

            val engine = QuickJs.create(Dispatchers.Default)
            engine.maxStackSize = 512 * 1024L
            try {
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
