package com.music.bitchord.data.sources.addon

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Small compatibility rules shared with LastWave's public addon client.
 *
 * The addon protocol is otherwise the same JSON HTTP protocol BitChord already
 * speaks. These rules are deliberately limited to the LastWave service and to
 * the two decoy markers its own resolver refuses.
 */
internal object LastWaveAddonCompatibility {

    /**
     * LastWave's hosted addon asks playback clients to identify stream requests
     * with its player intent header. Keep the profile restricted to that host;
     * unrelated addons should continue to see BitChord's normal user agent.
     */
    fun isHostedAddon(baseUrl: String): Boolean {
        val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == HOST || host.startsWith("lastwaveaddons.")
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

    private const val HOST = "lastwaveaddons.clashprojects.qd.je"
    private val DECOY_MARKERS = listOf("pranks-cdn", "definatelynagato")
}
