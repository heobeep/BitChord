package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Parsed Mornye package. The archive is never extracted until every path,
 * size, manifest and optional registry digest has been checked.
 */
data class MornyePackageArchive(
    val manifest: MornyeManifest,
    val code: String,
    val files: Map<String, ByteArray>,
)

data class MornyeManifest(
    val name: String,
    val displayName: String,
    val version: String,
    val types: List<String>,
    val networkPermissions: List<String>,
    val storagePermission: Boolean,
    val filePermission: Boolean,
    val minimumAppVersion: String?,
    val raw: JsonObject,
) {
    val canDownload: Boolean get() = "download_provider" in types
    val canSearch: Boolean get() = "metadata_provider" in types || canDownload
}

/** A verified row in the public Mornye extension registry. */
data class MornyeRegistryEntry(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String?,
    val category: String,
)

class MornyePackageException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Reader for Mornye's ZIP-based .sflx / .spotiflac-ext packages.
 *
 * Limits intentionally match the conservative bounds used by the existing
 * Lokal mobile host: 32 MiB compressed input, 64 MiB expanded data, 100
 * entries, and an 8 MiB JavaScript entry point.
 */
object MornyePackageReader {
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 100
    const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val idPattern = Regex("^[a-z0-9][a-z0-9_-]{0,99}$")
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][\\w.-]+)?$")
    private val domainPattern = Regex("^(?:\\*\\.)?[a-z0-9.-]+$", RegexOption.IGNORE_CASE)

    fun read(bytes: ByteArray, expectedSha256: String? = null): MornyePackageArchive {
        if (bytes.isEmpty() || bytes.size > MAX_ARCHIVE_BYTES) {
            throw MornyePackageException("Extension package must be between 1 byte and 32 MiB")
        }
        if (!expectedSha256.isNullOrBlank()) {
            if (!expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                throw MornyePackageException("Registry supplied an invalid SHA-256 digest")
            }
            val actual = sha256(bytes)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw MornyePackageException("Extension package checksum does not match the registry")
            }
        }

        val files = LinkedHashMap<String, ByteArray>()
        var expanded = 0
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) {
                        throw MornyePackageException("Extension package contains too many files")
                    }
                    val safeName = validateArchivePath(entry.name)
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (files.containsKey(safeName)) {
                        throw MornyePackageException("Extension package contains a duplicate path")
                    }

                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        if (expanded > MAX_EXPANDED_BYTES) {
                            throw MornyePackageException("Unpacked extension package exceeds 64 MiB")
                        }
                        output.write(buffer, 0, count)
                    }
                    files[safeName] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (e: MornyePackageException) {
            throw e
        } catch (e: Exception) {
            throw MornyePackageException("This is not a readable Mornye extension package", e)
        }

        val manifestBytes = files["manifest.json"]
            ?: throw MornyePackageException("Package is missing manifest.json at its root")
        val codeBytes = files["index.js"]
            ?: throw MornyePackageException("Package is missing index.js at its root")
        if (codeBytes.isEmpty() || codeBytes.size > MAX_SCRIPT_BYTES) {
            throw MornyePackageException("Extension script must be between 1 byte and 8 MiB")
        }

        val manifest = parseManifest(manifestBytes)
        val code = runCatching { codeBytes.toString(Charsets.UTF_8) }
            .getOrElse { throw MornyePackageException("Extension script is not valid UTF-8", it) }
        return MornyePackageArchive(manifest, code, files)
    }

    fun parseManifest(bytes: ByteArray): MornyeManifest {
        val root = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw MornyePackageException("Extension manifest is not valid JSON", e)
        }

        val name = root.string("name")
        val displayName = root.string("displayName")
        val version = root.string("version")
        if (!idPattern.matches(name)) {
            throw MornyePackageException("Manifest name must be a lowercase extension ID")
        }
        if (displayName.isBlank() || displayName.length > 120) {
            throw MornyePackageException("Manifest displayName is missing or too long")
        }
        if (!versionPattern.matches(version)) {
            throw MornyePackageException("Manifest version must use semantic versioning")
        }

        val types = root["type"] as? JsonArray
            ?: throw MornyePackageException("Manifest type must be an array")
        val typeNames = types.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val urlHandlerEnabled = root["urlHandler"]
            ?.let { runCatching { it.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull }.getOrNull() }
            ?: false
        val downloadable = "download_provider" in typeNames
        val linkReader = "metadata_provider" in typeNames && urlHandlerEnabled
        if (!downloadable && !linkReader) {
            throw MornyePackageException(
                "This BitChord preview supports download providers and metadata providers with URL handling",
            )
        }

        val permissions = root["permissions"] as? JsonObject
            ?: throw MornyePackageException("Manifest permissions must be an object")
        val network = permissions["network"] as? JsonArray
            ?: throw MornyePackageException("Manifest must declare network permissions")
        if (network.size > 100) {
            throw MornyePackageException("Manifest declares too many network domains")
        }
        val domains = network.map { element ->
            val domain = (element as? JsonPrimitive)?.contentOrNull
                ?: throw MornyePackageException("Network permission entries must be domain strings")
            if (!domainPattern.matches(domain) || domain.contains("..") || domain.startsWith(".") || domain.endsWith(".")) {
                throw MornyePackageException("Manifest contains an invalid network domain")
            }
            domain.lowercase()
        }.distinct()

        val storage = permissions["storage"]?.jsonPrimitive?.booleanOrNull ?: false
        val file = permissions["file"]?.jsonPrimitive?.booleanOrNull ?: false
        if (root["signedSession"] != null && !storage) {
            throw MornyePackageException("Signed sessions require the storage permission")
        }

        val settings = root["settings"] as? JsonArray
        val qualityOptions = root["qualityOptions"] as? JsonArray
        if ((settings?.size ?: 0) > 100 || (qualityOptions?.size ?: 0) > 30) {
            throw MornyePackageException("Manifest settings exceed the supported limits")
        }

        return MornyeManifest(
            name = name,
            displayName = displayName,
            version = version,
            types = typeNames,
            networkPermissions = domains,
            storagePermission = storage,
            filePermission = file,
            minimumAppVersion = root["minAppVersion"]?.jsonPrimitive?.contentOrNull,
            raw = root,
        )
    }

    private fun validateArchivePath(raw: String): String {
        if (raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\\\") || raw.contains('\\')) {
            throw MornyePackageException("Extension package contains an unsafe archive path")
        }
        val path = raw.removeSuffix("/")
        if (path.isBlank() || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            throw MornyePackageException("Extension package contains an unsafe archive path")
        }
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw MornyePackageException("Extension package contains an unsafe archive path")
        }
        return parts.joinToString("/")
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/** Parser for the registry.json format published by Mornye-Extension. */
object MornyeRegistryParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(body: String): List<MornyeRegistryEntry> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw MornyePackageException("Mornye registry is not valid JSON", e)
        }
        val entries = root["extensions"] as? JsonArray
            ?: throw MornyePackageException("Mornye registry has no extensions array")
        if (entries.size > 2_000) {
            throw MornyePackageException("Mornye registry contains too many entries")
        }
        val seen = HashSet<String>()
        return entries.mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val id = row.value("id")
            val version = row.value("version")
            val displayName = row.value("display_name").ifBlank { row.value("name") }
            val downloadUrl = row.value("download_url")
            val category = row.value("category")
            val uri = runCatching { URI(downloadUrl) }.getOrNull()
            if (!id.matches(Regex("^[a-z0-9][a-z0-9_-]{0,99}$")) ||
                version.isBlank() || displayName.isBlank() ||
                uri?.scheme != "https" || uri.host.isNullOrBlank() ||
                !seen.add(id)
            ) return@mapNotNull null

            val hash = row.value("sha256").takeIf { it.isNotBlank() }
            if (hash != null && !hash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                throw MornyePackageException("Registry entry $id has an invalid SHA-256 digest")
            }
            MornyeRegistryEntry(
                id = id,
                displayName = displayName,
                version = version,
                description = row.value("description"),
                downloadUrl = downloadUrl,
                sha256 = hash,
                category = category,
            )
        }
    }

    private fun JsonObject.value(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
