package com.music.bitchord.data.sources.mornye

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.UUID

/** A downloaded, inspected media file returned by a Mornye download provider. */
data class MornyeDownloadedAudio(
    val file: File?,
    val directUrl: String?,
    val format: StreamFormat,
    val durationSec: Int?,
    val metadata: JSONObject,
)

/**
 * One installed Mornye extension running in its own QuickJS instance.
 *
 * The session implements the lifecycle/search/availability/download sequence
 * used by current Mornye download providers. It intentionally keeps the
 * provider-specific result and file validation here, away from BitChord's
 * resolver and player.
 */
class MornyeExtensionSession private constructor(
    val manifest: MornyeManifest,
    private val runtime: MornyeQuickJsRuntime,
    private val host: MornyeNativeHost,
) : AutoCloseable {

    private val tracks = LinkedHashMap<String, JSONObject>()
    private val downloaded = LinkedHashMap<String, MornyeDownloadedAudio>()

    val methods: List<String> get() = runtime.methods
    val isClosed: Boolean get() = runtime.isClosed

    /** Maps BitChord's three quality rungs onto values declared by the package. */
    fun qualityFor(requested: String): String {
        val declared = manifest.raw["qualityOptions"] as? kotlinx.serialization.json.JsonArray
            ?: return requested
        if (declared.isEmpty()) return requested
        val candidates = declared.mapNotNull { element ->
            when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
                is kotlinx.serialization.json.JsonObject -> {
                    val id = element["id"]?.jsonPrimitive?.contentOrNull
                    val value = element["value"]?.jsonPrimitive?.contentOrNull
                    val label = element["label"]?.jsonPrimitive?.contentOrNull
                        ?: element["name"]?.jsonPrimitive?.contentOrNull
                    id ?: value ?: label
                }
                else -> null
            }
        }
        if (candidates.isEmpty()) return requested
        val words = when (requested.uppercase()) {
            "LOSSLESS" -> listOf("lossless", "flac", "hi-res", "hi_res", "hires", "max", "24-bit")
            "LOW" -> listOf("low", "128", "96", "64", "normal")
            else -> listOf("high", "320", "best", "max", "premium")
        }
        return candidates.firstOrNull { candidate ->
            words.any { word -> candidate.contains(word, ignoreCase = true) }
        } ?: if (requested.equals("LOSSLESS", true)) requested else candidates.first()
    }

    suspend fun search(query: String, limit: Int = 20): List<JSONObject> {
        if (query.isBlank()) return emptyList()
        val urlHandler = manifest.raw["urlHandler"]?.toString()?.contains("\"enabled\":true") == true
        val result = when {
            urlHandler && URL_LIKE.containsMatchIn(query) && "handleUrl" in methods ->
                invoke("handleUrl", JSONArray().put(query))
            "searchTracks" in methods ->
                invoke("searchTracks", JSONArray().put(query).put(limit.coerceIn(1, 100)))
            "customSearch" in methods -> {
                val filter = JSONObject().put("limit", limit.coerceIn(1, 100)).put("filter", "tracks")
                invoke("customSearch", JSONArray().put(query).put(filter))
            }
            else -> throw MornyePackageException("Extension does not provide track search")
        }
        val rows = unpackTracks(result)
        synchronized(tracks) {
            rows.forEach { row ->
                val id = trackId(row)
                if (id.isNotBlank()) tracks[id] = JSONObject(row.toString())
            }
        }
        return rows
    }

    suspend fun getTrack(id: String): JSONObject {
        synchronized(tracks) { tracks[id]?.let { return JSONObject(it.toString()) } }
        if ("getTrack" !in methods) {
            throw MornyePackageException("Extension does not provide track details")
        }
        val value = invoke("getTrack", JSONArray().put(id))
        val row = value as? JSONObject
            ?: throw MornyePackageException("Extension did not return track metadata")
        if (trackId(row).isBlank()) row.put("id", id)
        synchronized(tracks) { tracks[id] = JSONObject(row.toString()) }
        return row
    }

    /**
     * Checks provider availability, asks for enrichment once when supported,
     * then calls the extension's actual download method. The result is either
     * a direct stream URL or an app-private file that has been inspected.
     */
    suspend fun download(
        id: String,
        quality: String,
        expectedDurationMs: Long? = null,
    ): MornyeDownloadedAudio {
        val cacheKey = id + "|" + quality
        synchronized(downloaded) { downloaded[cacheKey]?.let { cached ->
            if (cached.file == null || cached.file.isFile && cached.file.length() > 0) return cached
        } }

        val track = runCatching { getTrack(id) }.getOrElse {
            JSONObject().put("id", id).put("name", "").put("artists", "")
                .put("duration_ms", expectedDurationMs ?: 0)
                .put("provider_id", manifest.name)
        }
        if (!track.has("duration_ms") && expectedDurationMs != null) {
            track.put("duration_ms", expectedDurationMs)
        }

        var availability = checkAvailability(track, id)
        if (availability.optBoolean("available", false).not() && "enrichTrack" in methods) {
            val enriched = invoke("enrichTrack", JSONArray().put(track)) as? JSONObject
            enriched?.let { mergeNonEmpty(track, it) }
            availability = checkAvailability(track, id)
        }
        if (!availability.optBoolean("available", false)) {
            throw MornyePackageException(
                availability.optString("reason").ifBlank { "The extension cannot download this track" },
            )
        }

        val selectedTrackId = availability.optString("track_id")
            .ifBlank { availability.optString("trackId").ifBlank { id } }
        val prepared = availability.optJSONObject("prepared_context")
            ?: availability.optJSONObject("preparedContext")
            ?: JSONObject()
        prepared.put("host_track", track)

        val workName = "streams/" + UUID.randomUUID().toString() + ".audio"
        val destination = host.localFile(workName)
        destination.parentFile?.mkdirs()
        val options = JSONObject()
            .put("preparedContext", prepared)
            .put("resolutionTimeoutMs", 60_000)
        val arguments = JSONArray()
            .put(selectedTrackId)
            .put(quality)
            .put(destination.absolutePath)
            .put(options)

        val result = invoke("download", arguments) as? JSONObject
            ?: throw MornyePackageException("Extension returned an invalid download result")
        if (!result.optBoolean("success", false)) {
            destination.delete()
            throw MornyePackageException(
                result.optString("error_message").ifBlank {
                    result.optString("error").ifBlank { "Extension download failed" }
                },
            )
        }

        val directUrl = firstString(result, "stream_url", "streamURL", "url")
        val audioFile = if (directUrl != null) {
            destination.takeIf { it.isFile && it.length() > 0 }?.delete()
            null
        } else {
            val returnedPath = firstString(result, "file_path", "filePath", "path")
            val file = if (returnedPath.isNullOrBlank()) destination else host.localFile(returnedPath)
            if (!file.isFile || file.length() <= 0) {
                throw MornyePackageException("Extension reported success but produced no audio file")
            }
            file
        }

        val info = if (audioFile != null) probe(audioFile) else formatFromMetadata(result, track)
        val expected = (track.opt("duration_ms") as? Number)?.toLong()
            ?: expectedDurationMs
        if (expected != null && info.durationSec != null) {
            val actualMs = info.durationSec * 1000L
            if (kotlin.math.abs(actualMs - expected) > 12_000L) {
                audioFile?.delete()
                throw MornyePackageException("Downloaded audio does not match the selected track duration")
            }
        }
        if (info.format.codec.isNullOrBlank() && directUrl == null) {
            audioFile?.delete()
            throw MornyePackageException("BitChord could not identify the downloaded audio format")
        }

        val answer = MornyeDownloadedAudio(
            file = audioFile,
            directUrl = directUrl,
            format = info.format,
            durationSec = info.durationSec,
            metadata = JSONObject(track.toString()).put("download_result", result),
        )
        synchronized(downloaded) { downloaded[cacheKey] = answer }
        return answer
    }

    private suspend fun checkAvailability(track: JSONObject, fallbackId: String): JSONObject {
        if ("checkAvailability" !in methods) {
            return JSONObject().put("available", true).put("track_id", fallbackId)
        }
        val options = JSONObject()
            .put("track", track)
            .put("duration_ms", track.optLong("duration_ms", 0))
        val directFields = mapOf(
            "qobuz" to "qobuz_id",
            "tidal" to "tidal_id",
            "deezer" to "deezer_id",
            "soundcloud" to "spotify_id",
            "amazon" to "spotify_id",
            "amazon-new" to "spotify_id",
        )
        val provider = manifest.name.lowercase()
        directFields[provider]?.let { field ->
            options.put(field, track.optString(field).ifBlank { fallbackId })
        }
        val args = JSONArray()
            .put(track.optString("isrc"))
            .put(track.optString("name", track.optString("title")))
            .put(artists(track))
            .put(options)
        return invoke("checkAvailability", args) as? JSONObject
            ?: JSONObject().put("available", false).put("reason", "Extension returned an invalid availability result")
    }

    private suspend fun invoke(method: String, args: JSONArray): Any? {
        val raw = runtime.invoke(method, args.toString())
        val value = try {
            JSONTokener(raw).nextValue()
        } catch (error: Exception) {
            throw MornyePackageException("Extension returned invalid JSON from " + method, error)
        }
        if (value is JSONObject && value.has("error")) {
            throw MornyePackageException(value.optString("error").ifBlank { "Extension operation failed" })
        }
        return value
    }

    private fun unpackTracks(value: Any?): List<JSONObject> {
        val list = when (value) {
            is JSONArray -> value
            is JSONObject -> {
                val album = value.optJSONObject("album")
                val artist = value.optJSONObject("artist")
                value.optJSONArray("tracks")
                    ?: album?.optJSONArray("tracks")
                    ?: artist?.optJSONArray("top_tracks")
                    ?: value.optJSONObject("track")?.let { JSONArray().put(it) }
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.takeIf {
                trackId(it).isNotBlank() && firstString(it, "name", "title") != null
            }
        }
    }

    private fun trackId(track: JSONObject): String =
        firstString(track, "id", "track_id", "trackId").orEmpty()

    private fun artists(track: JSONObject): String {
        val raw = track.opt("artists") ?: track.opt("artist")
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { index ->
                val item = raw.opt(index)
                when (item) {
                    is JSONObject -> firstString(item, "name", "title")
                    else -> item?.toString()
                }
            }.joinToString(", ")
            is JSONObject -> firstString(raw, "name", "title").orEmpty()
            else -> raw?.toString().orEmpty()
        }
    }

    private fun mergeNonEmpty(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            if (value != null && value != JSONObject.NULL && value != "" &&
                !(value is JSONArray && value.length() == 0)
            ) target.put(key, value)
        }
    }

    private fun probe(file: File): ProbeResult {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val media = extractor.getTrackFormat(index)
                val mime = media.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = codecForMime(mime)
                val durationMs = if (media.containsKey(MediaFormat.KEY_DURATION)) {
                    media.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else null
                val bitrate = if (media.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    (media.getInteger(MediaFormat.KEY_BIT_RATE) / 1000).takeIf { it > 0 }
                } else null
                val sampleRate = if (media.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    media.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                } else null
                val bitDepth = if (media.containsKey("bits-per-sample")) {
                    media.getInteger("bits-per-sample").takeIf { it > 0 }
                } else null
                return ProbeResult(
                    format = StreamFormat(codec, bitrate, sampleRate, bitDepth),
                    durationSec = durationMs?.let { (it / 1000L).toInt() },
                )
            }
            throw MornyePackageException("Downloaded file contains no decodable audio track")
        } catch (error: MornyePackageException) {
            throw error
        } catch (error: Exception) {
            val ext = file.extension.lowercase()
            val codec = when (ext) {
                "flac" -> "flac"
                "mp3" -> "mp3"
                "opus" -> "opus"
                "wav", "wave" -> "wav"
                "aiff", "aif" -> "aiff"
                "ape" -> "ape"
                "wv" -> "wv"
                else -> null
            } ?: throw MornyePackageException("BitChord could not inspect this downloaded audio", error)
            return ProbeResult(StreamFormat(codec), null)
        } finally {
            extractor.release()
        }
    }

    private fun formatFromMetadata(result: JSONObject, track: JSONObject): ProbeResult {
        val declared = firstString(result, "audio_quality", "audioQuality", "quality", "format").orEmpty()
        val codec = firstString(result, "codec", "mimeType", "mime_type")
            ?.substringAfterLast('/')
            ?.substringBefore(';')
            ?.lowercase()
            ?: when {
                declared.contains("flac", true) -> "flac"
                declared.contains("alac", true) -> "alac"
                declared.contains("atmos", true) -> "eac3-joc"
                else -> null
            }
        return ProbeResult(
            StreamFormat(codec = codec),
            (track.optLong("duration_ms", 0) / 1000L).takeIf { it > 0 }?.toInt(),
        )
    }

    private fun codecForMime(mime: String): String = when (mime.lowercase()) {
        "audio/flac" -> "flac"
        "audio/alac" -> "alac"
        "audio/mpeg" -> "mp3"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/eac3-joc" -> "eac3-joc"
        "audio/eac3", "audio/ec3" -> "eac3"
        "audio/raw" -> "pcm_s16le"
        "audio/mp4a-latm" -> "aac"
        else -> mime.substringAfter('/')
    }

    private data class ProbeResult(
        val format: StreamFormat,
        val durationSec: Int?,
    )

    override fun close() = runtime.close()

    companion object {
        private val URL_LIKE = Regex("^(?:https?:|[a-z]+:)", RegexOption.IGNORE_CASE)

        suspend fun open(
            context: Context,
            store: MornyeExtensionStore,
            extensionId: String,
        ): MornyeExtensionSession {
            val archive = store.openInstalled(extensionId)
            val host = MornyeNativeHost(context, archive.manifest, store.dataDirectory(extensionId))
            val settings = JSONObject()
            val declaredSettings = archive.manifest.raw["settings"]
            val settingsArray = declaredSettings as? kotlinx.serialization.json.JsonArray
            settingsArray?.forEach { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val key = item["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val default = item["default"] ?: item["defaultValue"] ?: return@forEach
                val value = JSONTokener(default.toString()).nextValue()
                settings.put(key, value)
            }
            val config = JSONObject()
                .put("permissions", JSONObject(archive.manifest.raw["permissions"]?.toString() ?: "{}"))
                .put("settings", settings)
                .put("signedSession", archive.manifest.raw["signedSession"]?.toString() ?: JSONObject.NULL)
            val runtime = MornyeQuickJsRuntime.start(
                extensionCode = archive.code,
                configJson = config.toString(),
                host = host,
            )
            val session = MornyeExtensionSession(archive.manifest, runtime, host)
            if ("initialize" in runtime.methods) {
                session.invoke("initialize", JSONArray().put(settings))
            }
            return session
        }

        private fun firstString(json: JSONObject, vararg keys: String): String? {
            for (key in keys) {
                val raw = json.opt(key)
                val value = when (raw) {
                    is String -> raw
                    is JSONObject -> raw.optString("url").ifBlank { raw.optString("name") }
                    else -> raw?.toString()
                }?.trim()
                if (!value.isNullOrBlank() && value != "null") return value
            }
            return null
        }
    }
}
