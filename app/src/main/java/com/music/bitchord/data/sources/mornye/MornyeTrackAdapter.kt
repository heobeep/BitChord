package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Converts Mornye's documented track and download objects into BitChord models. */
internal object MornyeTrackAdapter {

    fun toSong(track: JSONObject, configId: String, extensionName: String): Song? {
        val id = track.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = track.optString("name").ifBlank { track.optString("title") }
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val artistNames = readArtists(track.opt("artists"))
        val primaryArtist = artistNames.firstOrNull() ?: "Unknown Artist"
        val durationMs = track.optLong("duration_ms", -1L).takeIf { it > 0L }
            ?: track.optLong("durationMs", -1L).takeIf { it > 0L }
        val album = track.optString("album_name").takeIf { it.isNotBlank() && it != "null" }
        val artwork = readArtwork(track.opt("images"))
            ?: track.optString("cover_url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: track.optString("thumbnail").takeIf { it.startsWith("https://") || it.startsWith("http://") }

        return Song(
            videoId = SourceRegistry.trackKey(configId, id),
            title = title,
            artist = artistNames.joinToString(", ").ifBlank { primaryArtist },
            thumbnailUrl = artwork,
            durationText = durationMs?.let(::formatDuration),
            artists = artistNames.map { ArtistRef(it) },
            albumName = album,
            sourceQuality = track.optString("quality").takeIf { it.isNotBlank() && it != "null" },
            sourceHiRes = track.optInt("bit_depth", 0) >= 24,
            isExplicit = if (track.has("explicit")) track.optBoolean("explicit") else null,
            playbackSource = extensionName,
            playbackSourceType = PlaybackSourceType.SEARCH,
        )
    }

    fun toSourceStream(result: JSONObject): SourceStream? {
        if (!result.optBoolean("success", true)) return null
        val url = result.optString("url").takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        } ?: return null

        val headersObject = result.optJSONObject("headers") ?: JSONObject()
        val headers = buildMap {
            headersObject.keys().forEach { key ->
                val value = headersObject.optString(key)
                if (value.isNotBlank() && value != "null") put(key, value)
            }
        }
        val codec = result.optString("format").lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it != "null" }
        val bitDepth = result.optInt("bit_depth", 0).takeIf { it > 0 }
        val sampleRate = result.optInt("sample_rate", 0).takeIf { it > 0 }
        val kbps = result.optInt("bitrate_kbps", 0).takeIf { it > 0 }
        return SourceStream(
            url = url,
            format = StreamFormat(
                codec = codec,
                kbps = kbps,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
            ),
            headers = headers,
        )
    }

    private fun readArtists(value: Any?): List<String> = when (value) {
        is String -> value.split(',', ';').map(String::trim).filter(String::isNotBlank)
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is String -> item.trim().takeIf(String::isNotBlank)
                is JSONObject -> item.optString("name").takeIf(String::isNotBlank)
                else -> null
            }
        }
        is JSONObject -> value.optString("name").takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    private fun readArtwork(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { index ->
            when (val item = value.opt(index)) {
                is String -> item.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                is JSONObject -> item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                else -> null
            }
        }
        else -> null
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
