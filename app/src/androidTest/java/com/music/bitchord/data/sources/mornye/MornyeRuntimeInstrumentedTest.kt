package com.music.bitchord.data.sources.mornye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class MornyeRuntimeInstrumentedTest {

    @Test
    fun registersInitializesAndSearchesInOnePersistentContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
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
}
