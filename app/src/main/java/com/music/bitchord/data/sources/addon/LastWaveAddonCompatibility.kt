package com.music.bitchord.data.sources.addon

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * Compatibility rules for LastWave's hosted addon service.
 *
 * The wire protocol is otherwise the same JSON HTTP protocol BitChord already
 * speaks. These rules preserve LastWave's stream intent, Atmos selection and
 * decoy checks without changing how unrelated addons are requested.
 */
internal object LastWaveAddonCompatibility {

    /**
     * LastWave's hosted addon asks playback clients to identify stream requests
     * with its player intent header. Keep the profile restricted to that host;
     * unrelated addons should continue to see BitChord's normal user agent.
     */
    fun isHostedAddon(baseUrl: String): Boolean {
        val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == HOST
    }

    /**
     * LastWave's own player rejects these known prank CDN URLs before opening
     * them. Do the same here: a decoy can otherwise claim FLAC in JSON and be
     * mistaken for a valid lossless result.
     */
    fun isDecoyStream(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val value = url.lowercase()
        return DECOY_MARKERS.any(value::contains)
    }

    /**
     * LastWave's native client always sends an Atmos choice. Explicitly asking
     * for "none" prevents a server default from substituting an immersive mix
     * when the listener/device has Atmos disabled or chose a stereo row.
     */
    fun atmosParameter(baseUrl: String, atmosWanted: Boolean, stereoOnly: Boolean): String? {
        if (!isHostedAddon(baseUrl)) return null
        return if (atmosWanted && !stereoOnly) "auto" else "none"
    }

    /**
     * LastWave also treats the URL itself as evidence of a spatial rendition.
     * DASH data URLs need their manifest codec inspected, since the marker is
     * inside the base64 XML rather than visible in the URI.
     */
    fun isAtmosStreamUrl(url: String): Boolean {
        if (url.startsWith("data:application/dash+xml", ignoreCase = true)) {
            val encoded = url.substringAfter("base64,", "").trim()
            if (encoded.isEmpty()) return false
            val xml = runCatching {
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull() ?: return false
            return ATMOS_CODEC_HINT.containsMatchIn(xml)
        }
        val lower = url.lowercase()
        return lower.contains("atmos") || lower.contains("eac3") || lower.contains("ec-3")
    }

    private val ATMOS_CODEC_HINT = Regex("""(?:eac3|ec-3|ac-3)""", RegexOption.IGNORE_CASE)
    private const val HOST = "lastwaveaddons.clashprojects.qd.je"
    private val DECOY_MARKERS = listOf("pranks-cdn", "definatelynagato")
}
