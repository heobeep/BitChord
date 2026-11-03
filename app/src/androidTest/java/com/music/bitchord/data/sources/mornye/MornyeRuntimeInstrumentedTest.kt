package com.music.bitchord.data.sources.mornye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MornyeRuntimeInstrumentedTest {

    @Test
    fun registersInitializesAndSearchesInOnePersistentContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = context.assets.open("mornye/registration-smoke.js")
            .bufferedReader()
            .use { it.readText() }

        val runtime = MornyeRuntime.load(
            extensionId = "bitchord-runtime-smoke",
            extensionName = "BitChord Runtime Smoke Test",
            javascript = script,
        )

        try {
            runtime.initialize()
            val results = runtime.searchTracks("test query", 5)
            assertEquals(1, results.length())
            assertEquals("Runtime Smoke Test", results.getJSONObject(0).getString("name"))
            assertEquals("test query", results.getJSONObject(0).getString("query"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun rejectsScriptWithoutRegistration() = runBlocking {
        val error = runCatching {
            MornyeRuntime.load("missing-registration", "Missing registration", "var answer = 42;")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("registerExtension") == true)
    }

    @Test
    fun installsPackageAndRejectsTraversalAndClearsPrivateData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mornye-package-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val manager = MornyePackageManager(root)
        val packageFile = File(context.cacheDir, "smoke.spotiflac-ext")
        writeZip(packageFile, listOf(
            "manifest.json" to """{"name":"smoke-provider","displayName":"Smoke Provider","version":"1.0.0","description":"Test extension","author":"BitChord tests","permissions":{"network":["api.example.com"],"storage":true,"file":false},"type":["metadata_provider"]}""",
            "index.js" to "registerExtension({ searchTracks: function() { return []; } });",
            "assets/" to "",
        ))

        try {
            val installed = manager.install(packageFile)
            assertEquals("smoke-provider", installed.manifest.name)
            assertEquals(listOf("api.example.com"), installed.manifest.networkDomains)
            assertTrue(installed.script.isFile)
            assertEquals(1, manager.listInstalled().size)

            installed.dataDirectory.mkdirs()
            File(installed.dataDirectory, "private.json").writeText("""{"token":"test"}""")
            assertTrue(manager.uninstall("smoke-provider"))
            assertTrue(manager.listInstalled().isEmpty())
            assertFalse(installed.dataDirectory.exists())
        } finally {
            packageFile.delete()
            root.deleteRecursively()
        }

        val malicious = File(context.cacheDir, "mornye-traversal.spotiflac-ext")
        writeZip(malicious, listOf("../outside.txt" to "not allowed"))
        val error = runCatching { manager.install(malicious) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        malicious.delete()
    }

    @Test
    fun mapsMornyeTrackAndStreamToBitChordModels() {
        val track = JSONObject(
            """{"id":"track-123","name":"Example Song","artists":"Artist One; Artist Two","album_name":"Example Album","duration_ms":225000,"images":"https://img.example.com/cover.jpg","bit_depth":24}"""
        )
        val song = MornyeTrackAdapter.toSong(track, "extension-config", "Example Provider")
        assertEquals("src:extension-config::track-123", song?.videoId)
        assertEquals("Example Song", song?.title)
        assertEquals("Artist One, Artist Two", song?.artist)
        assertEquals("3:45", song?.durationText)
        assertTrue(song?.sourceHiRes == true)

        val stream = MornyeTrackAdapter.toSourceStream(
            JSONObject("""{"success":true,"url":"https://cdn.example.com/audio.flac","format":"flac","bit_depth":24,"sample_rate":96000,"headers":{"Referer":"https://example.com"}}""")
        )
        assertEquals("https://cdn.example.com/audio.flac", stream?.url)
        assertEquals(24, stream?.format?.bitDepth)
        assertEquals("https://example.com", stream?.headers?.get("Referer"))
    }

    private fun writeZip(file: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                if (content.isNotEmpty()) zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
