package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A locally installed Mornye package exposed through BitChord's normal source pipeline. */
internal class MornyeExtensionSource(
    override val config: SourceConfig,
    private val packages: MornyePackageManager,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    private val runtimeLock = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runtime: MornyeRuntime? = null
    @Volatile private var loadedVersion: String? = null

    private val extensionId: String
        get() = config.extensionId ?: error("Mornye extension id is missing")

    override suspend fun health(): SourceHealth {
        return try {
            val installed = packages.get(extensionId)
                ?: return SourceHealth.Rejected("Extension is not installed")
            if ("metadata_provider" !in installed.manifest.types) {
                return SourceHealth.Rejected("This extension does not provide track search")
            }
            ensureRuntime(installed)
            SourceHealth.Ok(installed.manifest.displayName + " " + installed.manifest.version)
        } catch (error: Throwable) {
            SourceHealth.Unreachable(error.message ?: "Extension could not be loaded")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val installed = packages.get(extensionId) ?: return emptyList()
        if ("metadata_provider" !in installed.manifest.types) return emptyList()
        val active = ensureRuntime(installed)
        val results = active.searchTracks(query, limit.coerceIn(1, 100))
        return (0 until results.length()).mapNotNull { index ->
            MornyeTrackAdapter.toSong(
                track = results.optJSONObject(index) ?: return@mapNotNull null,
                configId = configId,
                extensionName = displayName,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val installed = packages.get(extensionId) ?: return null
        if ("download_provider" !in installed.manifest.types) return null
        val quality = when (request) {
            StreamRequest.Lossless -> "LOSSLESS"
            is StreamRequest.Capped -> if (request.maxKbps >= 256) "HIGH" else "LOW"
            StreamRequest.Best -> "HIGH"
        }
        val result = ensureRuntime(installed).getDownloadUrl(trackId, quality)
        return MornyeTrackAdapter.toSourceStream(result)
    }

    /** Stops the old JS context when a source is edited, disabled or removed. */
    fun release() {
        val old = runtime
        runtime = null
        loadedVersion = null
        if (old != null) cleanupScope.launch { runCatching { old.close() } }
    }

    private suspend fun ensureRuntime(
        installed: MornyePackageManager.InstalledExtension,
    ): MornyeRuntime = runtimeLock.withLock {
        val current = runtime
        if (current != null && loadedVersion == installed.manifest.version) return@withLock current
        if (current != null) runCatching { current.close() }

        val loaded = MornyeRuntime.load(installed, packages.applicationContext)
        try {
            loaded.initialize()
        } catch (error: Throwable) {
            runCatching { loaded.close() }
            throw error
        }
        runtime = loaded
        loadedVersion = installed.manifest.version
        TrackLog.d("BitChord", "Loaded Mornye extension ${installed.manifest.name} ${installed.manifest.version}")
        loaded
    }
}
