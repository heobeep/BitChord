package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastWaveAddonCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `directUrl alias is accepted when url is absent`() {
        val stream = json.decodeFromString<AddonStream>(
            """{"directUrl":"https://cdn.example/audio","format":"flac","codec":"flac"}""",
        )

        assertEquals("https://cdn.example/audio", stream.playableUrl)
        assertEquals("flac", stream.statedCodec)
    }

    @Test
    fun `streamUrl downloadUrl and mediaUrl aliases are accepted`() {
        val streamUrl = json.decodeFromString<AddonStream>( """{"streamUrl":"https://cdn.example/stream"}""")
        val downloadUrl = json.decodeFromString<AddonStream>( """{"downloadUrl":"https://cdn.example/download"}""")
        val mediaUrl = json.decodeFromString<AddonStream>( """{"mediaUrl":"https://cdn.example/media"}""")

        assertEquals("https://cdn.example/stream", streamUrl.playableUrl)
        assertEquals("https://cdn.example/download", downloadUrl.playableUrl)
        assertEquals("https://cdn.example/media", mediaUrl.playableUrl)
    }

    @Test
    fun `blank URL aliases remain a clean miss`() {
        val stream = json.decodeFromString<AddonStream>( """{"url":" ","directUrl":""}""")

        assertNull(stream.playableUrl)
    }

    @Test
    fun `standard url retains priority over aliases`() {
        val stream = json.decodeFromString<AddonStream>(
            """{"url":"https://cdn.example/primary","directUrl":"https://cdn.example/alternate"}""",
        )

        assertEquals("https://cdn.example/primary", stream.playableUrl)
    }

    @Test
    fun `known LastWave decoy URLs are identified and rejected`() {
        val prankUrl = json.decodeFromString<AddonStream>(
            """{"url":"https://pranks-cdn.example/track.mp3"}""",
        )
        val honeypotUrl = json.decodeFromString<AddonStream>(
            """{"directUrl":"https://cdn.example/definatelynagato/track.mp3"}""",
        )
        val decoyManifest = json.decodeFromString<AddonStream>(
            """{"manifestXml":"<MPD><BaseURL>https://pranks-cdn.example/</BaseURL></MPD>"}""",
        )
        val clean = json.decodeFromString<AddonStream>(
            """{"url":"https://cdn.example/real.flac"}""",
        )

        assertEquals(true, prankUrl.isKnownLastWaveDecoy)
        assertEquals(true, honeypotUrl.isKnownLastWaveDecoy)
        assertEquals(true, decoyManifest.isKnownLastWaveDecoy)
        assertEquals(false, clean.isKnownLastWaveDecoy)
    }
}
