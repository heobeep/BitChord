package com.music.bitchord.data.sources.mornye

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Installs Mornye packages in app-private storage.
 *
 * Package code is data until a caller explicitly starts its isolated QuickJS
 * runtime. Installs are staged and swapped atomically so an interrupted update
 * cannot leave a half-written extension on disk.
 */
class MornyeExtensionStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "mornye/extensions")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun install(entry: MornyeRegistryEntry): MornyeManifest = withContext(Dispatchers.IO) {
        val uri = runCatching { java.net.URI(entry.downloadUrl) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Extension package URL must use HTTPS"
        }
        val request = Request.Builder().url(entry.downloadUrl).get().build()
        val response = client.newCall(request).execute()
        val bytes = response.use {
            if (!it.isSuccessful) throw IOException("Extension download returned HTTP ${it.code}")
            val body = it.body ?: throw IOException("Extension download was empty")
            if (body.contentLength() > MornyePackageReader.MAX_ARCHIVE_BYTES) {
                throw MornyePackageException("Extension package exceeds 32 MiB")
            }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MornyePackageReader.MAX_ARCHIVE_BYTES) {
                        throw MornyePackageException("Extension package exceeds 32 MiB")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

        val archive = MornyePackageReader.read(bytes, entry.sha256)
        if (archive.manifest.name != entry.id || archive.manifest.version != entry.version) {
            throw MornyePackageException("Downloaded package identity does not match its registry entry")
        }
        installArchive(archive)
        archive.manifest
    }

    suspend fun installed(): List<MornyeManifest> = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.matches(Regex("^[a-z0-9][a-z0-9_-]{0,99}$")) }
            .mapNotNull { directory ->
                runCatching {
                    val file = File(directory, "manifest.json")
                    MornyePackageReader.parseManifest(file.readBytes())
                }.getOrNull()
            }
            .toList()
    }

    suspend fun remove(extensionId: String) = withContext(Dispatchers.IO) {
        require(extensionId.matches(Regex("^[a-z0-9][a-z0-9_-]{0,99}$"))) {
            "Invalid extension ID"
        }
        val target = File(root, extensionId)
        if (target.exists() && !target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw MornyePackageException("Refusing to remove a path outside extension storage")
        }
        target.deleteRecursively()
    }

    internal fun packageDirectory(extensionId: String): File {
        require(extensionId.matches(Regex("^[a-z0-9][a-z0-9_-]{0,99}$"))) {
            "Invalid extension ID"
        }
        return File(root, extensionId)
    }

    private fun installArchive(archive: MornyePackageArchive) {
        val id = archive.manifest.name
        root.mkdirs()
        val destination = File(root, id)
        val staging = File(root, ".$id-installing")
        val backup = File(root, ".$id-backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Could not create extension staging directory")

        try {
            for ((relative, content) in archive.files) {
                val output = File(staging, relative)
                if (!output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw MornyePackageException("Extension package path escaped its staging directory")
                }
                output.parentFile?.mkdirs()
                output.outputStream().use { it.write(content) }
            }

            if (destination.exists() && !destination.renameTo(backup)) {
                throw IOException("Could not stage the previous extension version")
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IOException("Could not activate the installed extension")
            }
            backup.deleteRecursively()
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw error
        }
    }
}
