package com.music.bitchord.data.sources.mornye

import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest

/**
 * A native BitChord source backed by one installed Mornye extension.
 *
 * Search/metadata is handled by the extension runtime. The extension's own
 * download() method produces a controlled local file; BitChord probes that
 * file and returns a file:// URI to the existing Media3 data-source stack.
 */
class MornyeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    override suspend fun health(): SourceHealth = runCatching {
        val session = MornyeExtensionManager.session(config.baseUrl)
        SourceHealth.Ok(session.manifest.displayName + " · v" + session.manifest.version)
    }.getOrElse { failure ->
        SourceHealth.Rejected(failure.message ?: "Mornye extension could not be started")
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        val session = MornyeExtensionManager.session(config.baseUrl)
        return session.search(query, limit).mapNotNull { track ->
            val id = track.optString("id").ifBlank {
                track.optString("track_id").ifBlank { track.optString("trackId") }
            }
            if (id.isBlank()) return@mapNotNull null

            val title = first(track, "name", "title") ?: return@mapNotNull null
            val artist = artists(track).ifBlank { "Unknown Artist" }
            val album = albumName(track)
            val durationMs = durationMs(track)
            val durationText = durationMs?.let { ms ->
                val seconds = (ms / 1000L).toInt()
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            }
            val quality = first(track, "audio_quality", "audioQuality", "quality", "format")
            Song(
                videoId = SourceRegistry.trackKey(config.id, id),
                title = title,
                artist = artist,
                albumName = album,
                thumbnailUrl = first(track, "artwork_url", "artworkURL", "image_url", "thumbnail", "cover"),
                durationText = durationText,
                sourceQuality = quality,
                sourceHiRes = quality?.contains("hi-res", ignoreCase = true) == true ||
                    quality?.contains("hi_res", ignoreCase = true) == true,
            )
        }.take(limit.coerceIn(1, 100))
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val session = MornyeExtensionManager.session(config.baseUrl)
        val requestedQuality = when (request) {
            StreamRequest.Lossless -> "LOSSLESS"
            StreamRequest.Best -> "HIGH"
            is StreamRequest.Capped -> if (request.maxKbps <= 128) "LOW" else "HIGH"
        }
        val quality = session.qualityFor(requestedQuality)
        val audio = session.download(trackId, quality)
        val format = audio.format
        when (request) {
            StreamRequest.Lossless -> if (format.isLossless != true) {
                audio.file?.delete()
                return null
            }
            is StreamRequest.Capped -> if (format.kbps != null && format.kbps > request.maxKbps) {
                audio.file?.delete()
                return null
            }
            StreamRequest.Best -> Unit
        }
        val url = audio.directUrl ?: audio.file?.let { Uri.fromFile(it).toString() } ?: return null
        return SourceStream(
            url = url,
            format = format,
            durationSec = audio.durationSec,
        )
    }

    private fun first(track: org.json.JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = track.opt(key)
            val candidate = when (value) {
                is String -> value
                is org.json.JSONObject -> value.optString("url").ifBlank { value.optString("name") }
                else -> value?.toString()
            }?.trim()
            if (!candidate.isNullOrBlank() && candidate != "null") return candidate
        }
        return null
    }

    private fun artists(track: org.json.JSONObject): String {
        val values = track.opt("artists") ?: track.opt("artist")
        return when (values) {
            is org.json.JSONArray -> (0 until values.length()).mapNotNull { index ->
                val value = values.opt(index)
                when (value) {
                    is org.json.JSONObject -> first(value, "name", "title")
                    else -> value?.toString()
                }
            }.joinToString(", ")
            is org.json.JSONObject -> first(values, "name", "title").orEmpty()
            else -> values?.toString().orEmpty()
        }
    }

    private fun albumName(track: org.json.JSONObject): String? {
        first(track, "album_name", "albumName", "album_title")?.let { return it }
        val album = track.optJSONObject("album") ?: return null
        return first(album, "name", "title", "album_name")
    }

    private fun durationMs(track: org.json.JSONObject): Long? {
        val raw = track.opt("duration_ms") ?: track.opt("durationMs")
        if (raw is Number) return raw.toLong().takeIf { it > 0 }
        val duration = track.opt("duration")
        if (duration is Number) return (duration.toLong() * 1000L).takeIf { it > 0 }
        return duration?.toString()?.toLongOrNull()?.let { (it * 1000L).takeIf { value -> value > 0 } }
    }
}
