package com.music.bitchord.data.sources.mornye

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs and manages Mornye .spotiflac-ext ZIP packages in app-private storage.
 *
 * The archive is treated as untrusted input: extraction is bounded, paths are
 * normalized and checked, duplicate entries are rejected, and installation is
 * swapped into place only after the complete package validates.
 */
class MornyePackageManager internal constructor(
    private val root: File,
    internal val applicationContext: Context? = null,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "mornye/extensions"),
        context.applicationContext,
    )
    private val lock = Any()

    data class Manifest(
        val name: String,
        val displayName: String,
        val version: String,
        val description: String,
        val author: String,
        val entryPoint: String,
        val networkDomains: List<String>,
        val storagePermission: Boolean,
        val filePermission: Boolean,
        val types: List<String>,
        val raw: JSONObject,
    )

    data class InstalledExtension(
        val manifest: Manifest,
        val directory: File,
        val sha256: String,
    ) {
        val script: File get() = File(directory, manifest.entryPoint)
        /** Persists across extension upgrades; executable package files do not. */
        val dataDirectory: File
            get() = File(File(directory.parentFile?.parentFile, "data"), manifest.name)
    }

    /**
     * Installs a package from a downloaded or user-selected file.
     * The caller retains ownership of [packageFile].
     */
    fun install(packageFile: File): InstalledExtension = synchronized(lock) {
        require(packageFile.isFile && packageFile.length() > 0L) { "Choose a valid .spotiflac-ext package" }
        require(packageFile.length() <= MAX_ARCHIVE_BYTES) { "Extension package is too large" }
        if (!root.exists() && !root.mkdirs()) throw IOException("Cannot create extension storage")

        val digest = sha256(packageFile)
        val staging = File(root, ".install-${System.nanoTime()}")
        val destination: File
        var backup: File? = null
        try {
            if (!staging.mkdirs()) throw IOException("Cannot create temporary install directory")
            extractAndValidate(packageFile, staging)
            val manifest = readManifest(staging)
            destination = File(root, manifest.name)

            val current = if (destination.isDirectory) runCatching { readInstalled(destination) }.getOrNull() else null
            if (current != null) {
                val comparison = compareVersions(manifest.version, current.manifest.version)
                require(comparison > 0) {
                    if (comparison == 0) "This extension version is already installed"
                    else "Older extension versions cannot replace a newer installation"
                }
            }

            if (destination.exists()) {
                backup = File(root, ".backup-${manifest.name}-${System.nanoTime()}")
                moveDirectory(destination, backup)
            }
            try {
                moveDirectory(staging, destination)
            } catch (error: Throwable) {
                backup?.let { if (it.exists()) moveDirectory(it, destination) }
                throw error
            }
            backup?.deleteRecursively()
            InstalledExtension(manifest, destination, digest)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            // Keep a backup if rollback itself failed; never destroy the last known-good install.
            throw error
        }
    }

    fun listInstalled(): List<InstalledExtension> = synchronized(lock) {
        if (!root.isDirectory) return@synchronized emptyList()
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir -> runCatching { readInstalled(dir) }.getOrNull() }
            ?.sortedBy { it.manifest.displayName.lowercase(Locale.ROOT) }
            ?.toList()
            .orEmpty()
    }

    fun get(extensionId: String): InstalledExtension? = synchronized(lock) {
        if (!ID_PATTERN.matches(extensionId)) return@synchronized null
        val directory = File(root, extensionId)
        if (!directory.isDirectory) null else runCatching { readInstalled(directory) }.getOrNull()
    }

    fun uninstall(extensionId: String): Boolean = synchronized(lock) {
        require(ID_PATTERN.matches(extensionId)) { "Invalid extension id" }
        val target = File(root, extensionId)
        if (!target.exists()) return@synchronized false
        require(target.canonicalFile.parentFile == root.canonicalFile) { "Invalid extension location" }
        val removedPackage = target.deleteRecursively()
        if (!removedPackage) return@synchronized false
        val dataRoot = File(root.parentFile, "data").canonicalFile
        val dataDirectory = File(dataRoot, extensionId).canonicalFile
        require(dataDirectory.parentFile == dataRoot) { "Invalid extension data location" }
        !dataDirectory.exists() || dataDirectory.deleteRecursively()
    }

    private fun readInstalled(directory: File): InstalledExtension {
        val manifest = readManifest(directory)
        return InstalledExtension(manifest, directory, "")
    }

    private fun extractAndValidate(archive: File, destination: File) {
        val seen = HashSet<String>()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "Package contains too many files" }
                val relative = safeRelativePath(entry.name)
                require(seen.add(relative)) { "Package contains duplicate file paths" }
                if (entry.isDirectory) {
                    val dir = File(destination, relative)
                    require(dir.canonicalPath.startsWith(destination.canonicalPath + File.separator))
                    if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create package directory")
                    zip.closeEntry()
                    continue
                }

                val output = File(destination, relative)
                require(output.canonicalFile.parentFile?.let {
                    it == destination.canonicalFile || it.path.startsWith(destination.canonicalPath + File.separator)
                } == true) { "Package contains an unsafe path" }
                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create package directory") }

                var fileBytes = 0L
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        fileBytes += read
                        totalBytes += read
                        require(fileBytes <= MAX_FILE_BYTES) { "A package file is too large" }
                        require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "Unpacked extension is too large" }
                        sink.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(seen.contains("manifest.json")) { "Package is missing manifest.json at its root" }
        require(seen.contains("index.js")) { "Package is missing index.js at its root" }
        readManifest(destination)
    }

    private fun readManifest(directory: File): Manifest {
        val file = File(directory, "manifest.json")
        require(file.isFile && file.length() in 2..MAX_MANIFEST_BYTES) { "Invalid or missing manifest.json" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val name = json.optString("name")
        val displayName = json.optString("displayName")
        val version = json.optString("version")
        val description = json.optString("description")
        val author = json.optString("author")
        require(ID_PATTERN.matches(name)) { "Manifest name must be lowercase and contain no spaces" }
        require(displayName.isNotBlank()) { "Manifest displayName is required" }
        require(VERSION_PATTERN.matches(version)) { "Manifest version must be semantic (x.y.z)" }
        require(description.isNotBlank()) { "Manifest description is required" }
        require(author.isNotBlank()) { "Manifest author is required" }

        val entryPoint = json.optString("entryPoint", "index.js")
        val safeEntry = safeRelativePath(entryPoint)
        require(safeEntry == entryPoint && entryPoint.endsWith(".js", ignoreCase = true)) {
            "Manifest entryPoint must be a relative JavaScript file"
        }
        require(File(directory, entryPoint).isFile) { "Manifest entryPoint file was not found" }

        val permissions = json.optJSONObject("permissions")
            ?: throw IllegalArgumentException("Manifest permissions object is required")
        val network = permissions.optJSONArray("network") ?: JSONArray()
        val domains = (0 until network.length()).map { index ->
            val domain = network.optString(index).trim().lowercase(Locale.ROOT)
            require(isValidDomainPattern(domain)) { "Invalid network permission domain: $domain" }
            domain
        }.distinct()
        val typesArray = json.optJSONArray("type")
            ?: throw IllegalArgumentException("Manifest type array is required")
        val supportedTypes = setOf("metadata_provider", "download_provider", "lyrics_provider")
        val types = (0 until typesArray.length()).map { index ->
            typesArray.optString(index).also {
                require(it in supportedTypes) { "Unsupported extension type: $it" }
            }
        }.distinct()
        require(types.isNotEmpty()) { "Manifest must declare at least one extension type" }

        return Manifest(
            name = name,
            displayName = displayName,
            version = version,
            description = description,
            author = author,
            entryPoint = entryPoint,
            networkDomains = domains,
            storagePermission = permissions.optBoolean("storage", false),
            filePermission = permissions.optBoolean("file", false),
            types = types,
            raw = json,
        )
    }

    private fun safeRelativePath(raw: String): String {
        val normalized = raw.removeSuffix("/")
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.startsWith('\\')) { "Package contains an absolute path" }
        require(!normalized.contains('\\') && !normalized.contains('\u0000')) { "Package contains an invalid path" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Package contains a path traversal entry" }
        return parts.joinToString(File.separator)
    }

    private fun isValidDomainPattern(domain: String): Boolean {
        val candidate = domain.removePrefix("*.")
        return candidate.length <= 253 &&
            candidate.split('.').size >= 2 &&
            candidate.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveDirectory(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore('-').split('.').map { it.toInt() }
        val b = right.substringBefore('-').split('.').map { it.toInt() }
        for (index in 0..2) {
            val difference = a[index].compareTo(b[index])
            if (difference != 0) return difference
        }
        val leftPre = left.substringAfter('-', "")
        val rightPre = right.substringAfter('-', "")
        return when {
            leftPre.isEmpty() && rightPre.isNotEmpty() -> 1
            leftPre.isNotEmpty() && rightPre.isEmpty() -> -1
            else -> leftPre.compareTo(rightPre)
        }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")
        const val MAX_ARCHIVE_BYTES = 25L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val MAX_ENTRIES = 256
    }
}
