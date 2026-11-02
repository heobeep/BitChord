package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * App-wide owner for the Mornye package catalogue, installed extensions and
 * warm per-extension runtimes. The native player still owns the queue and
 * playback; this manager only supplies extension sessions.
 */
object MornyeExtensionManager {
    const val DEFAULT_REGISTRY =
        "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json"

    private lateinit var appContext: Context
    private lateinit var store: MornyeExtensionStore
    private val sessions = ConcurrentHashMap<String, MornyeExtensionSession>()
    private val sessionLock = Mutex()

    private val registryClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = MornyeExtensionStore(appContext)
        initialized = true
    }

    private fun requireInitialized() {
        check(initialized) { "Mornye extension manager has not been initialized" }
    }

    suspend fun refreshRegistry(url: String = DEFAULT_REGISTRY): List<MornyeRegistryEntry> =
        withContext(Dispatchers.IO) {
            requireInitialized()
            val parsed = java.net.URI(url)
            require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) {
                "Mornye registry URL must use HTTPS"
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "BitChord/" + com.music.bitchord.BuildConfig.VERSION_NAME)
                .get()
                .build()
            registryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Mornye registry returned HTTP " + response.code)
                val body = response.body ?: throw IOException("Mornye registry response was empty")
                if (body.contentLength() > 2 * 1024 * 1024) {
                    throw MornyePackageException("Mornye registry is too large")
                }
                val text = body.string()
                if (text.length > 2 * 1024 * 1024) throw MornyePackageException("Mornye registry is too large")
                MornyeRegistryParser.parse(text)
            }
        }

    suspend fun install(entry: MornyeRegistryEntry): MornyeManifest {
        requireInitialized()
        val manifest = store.install(entry)
        val existing = SourceRegistry.configs.value.firstOrNull {
            it.kind == SourceKind.MORNYE && it.baseUrl == manifest.name
        }
        val config = (existing ?: SourceConfig(
            kind = SourceKind.MORNYE,
            baseUrl = manifest.name,
            label = manifest.displayName,
        )).copy(
            kind = SourceKind.MORNYE,
            baseUrl = manifest.name,
            label = manifest.displayName,
        )
        if (existing == null) SourceRegistry.add(config) else SourceRegistry.update(config)
        closeSession(manifest.name)
        return manifest
    }

    suspend fun installed(): List<MornyeManifest> {
        requireInitialized()
        return store.installed()
    }

    suspend fun remove(extensionId: String) {
        requireInitialized()
        closeSession(extensionId)
        store.remove(extensionId)
        SourceRegistry.configs.value
            .firstOrNull { it.kind == SourceKind.MORNYE && it.baseUrl == extensionId }
            ?.let { SourceRegistry.remove(it.id) }
    }

    suspend fun session(extensionId: String): MornyeExtensionSession {
        requireInitialized()
        sessions[extensionId]?.takeIf { !it.isClosed }?.let { return it }
        return sessionLock.withLock {
            sessions[extensionId]?.takeIf { !it.isClosed }?.let { return@withLock it }
            sessions.remove(extensionId)?.close()
            MornyeExtensionSession.open(appContext, store, extensionId).also {
                sessions[extensionId] = it
            }
        }
    }

    fun closeSession(extensionId: String) {
        sessions.remove(extensionId)?.close()
    }

    fun shutdown() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
