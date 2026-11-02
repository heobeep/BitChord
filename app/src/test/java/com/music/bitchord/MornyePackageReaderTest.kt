package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MornyePackageException
import com.music.bitchord.data.sources.mornye.MornyePackageReader
import com.music.bitchord.data.sources.mornye.MornyeRegistryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MornyePackageReaderTest {
    @Test
    fun `reads a valid download-provider package`() {
        val bytes = archive(
            manifest = manifest(),
            script = "registerExtension({ initialize() {}, searchTracks(q) { return [{id:'1', name:q}] } })",
        )

        val parsed = MornyePackageReader.read(bytes)

        assertEquals("soundcloud", parsed.manifest.name)
        assertEquals("SoundCloud", parsed.manifest.displayName)
        assertEquals("1.0.0", parsed.manifest.version)
        assertTrue(parsed.manifest.canDownload)
        assertEquals("registerExtension({ initialize() {}, searchTracks(q) { return [{id:'1', name:q}] } })", parsed.code)
        assertTrue(parsed.manifest.networkPermissions.contains("api.example.com"))
    }

    @Test
    fun `rejects archive traversal paths`() {
        val bytes = archive(
            manifest = manifest(),
            script = "registerExtension({ searchTracks() {} })",
            extra = "../outside.txt" to "no",
        )
        assertThrows(MornyePackageException::class.java) {
            MornyePackageReader.read(bytes)
        }
    }

    @Test
    fun `accepts the prepared context feature used by JioSaavn`() {
        val bytes = archive(
            manifest = manifest().replace(
                "\"type\": [\"download_provider\"]",
                "\"type\": [\"metadata_provider\", \"download_provider\"], \"requiredRuntimeFeatures\": [\"preparedContext@1\"]",
            ),
            script = "registerExtension({ initialize() {}, searchTracks() { return [] } })",
        )

        val parsed = MornyePackageReader.read(bytes)

        assertTrue(parsed.manifest.canDownload)
    }

    @Test
    fun `rejects runtime features that BitChord has not implemented`() {
        val bytes = archive(
            manifest = manifest().replace(
                "\"type\": [\"download_provider\"]",
                "\"type\": [\"download_provider\"], \"requiredRuntimeFeatures\": [\"signedSession@1\"]",
            ),
            script = "registerExtension({ download() { return {success:false} } })",
        )
        assertThrows(MornyePackageException::class.java) {
            MornyePackageReader.read(bytes)
        }
    }

    @Test
    fun `rejects a package with no root entry point`() {
        val bytes = archive(
            manifest = manifest(),
            script = "registerExtension({})",
            scriptPath = "nested/index.js",
        )
        assertThrows(MornyePackageException::class.java) {
            MornyePackageReader.read(bytes)
        }
    }

    @Test
    fun `enforces a registry checksum`() {
        val bytes = archive(manifest = manifest(), script = "registerExtension({})")
        assertThrows(MornyePackageException::class.java) {
            MornyePackageReader.read(bytes, "0".repeat(64))
        }
    }

    @Test
    fun `reads Mornye registry entries and skips unsafe URLs`() {
        val hash = "a".repeat(64)
        val registry = """
            {
              "version": 1,
              "extensions": [
                {
                  "id": "soundcloud",
                  "display_name": "SoundCloud",
                  "version": "1.0.14",
                  "description": "Audio provider",
                  "download_url": "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/extensions/soundcloud.sflx",
                  "sha256": "$hash",
                  "category": "download"
                },
                {
                  "id": "unsafe",
                  "display_name": "Unsafe",
                  "version": "1.0.0",
                  "download_url": "http://example.com/addon.sflx",
                  "category": "download"
                }
              ]
            }
        """.trimIndent()

        val entries = MornyeRegistryParser.parse(registry)

        assertEquals(1, entries.size)
        assertEquals("soundcloud", entries.single().id)
        assertEquals(hash, entries.single().sha256)
    }

    private fun archive(
        manifest: String,
        script: String,
        scriptPath: String = "index.js",
        extra: Pair<String, String>? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(scriptPath))
            zip.write(script.toByteArray())
            zip.closeEntry()
            extra?.let { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun manifest(): String = """
        {
          "name": "soundcloud",
          "displayName": "SoundCloud",
          "version": "1.0.0",
          "type": ["download_provider"],
          "permissions": {
            "network": ["api.example.com"],
            "storage": true,
            "file": true
          }
        }
    """.trimIndent()
}
