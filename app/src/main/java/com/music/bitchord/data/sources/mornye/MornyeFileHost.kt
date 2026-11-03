package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import java.io.File
import java.io.IOException

/** Relative-path file access confined to one extension's private data folder. */
internal class MornyeFileHost(
    private val root: File,
    private val enabled: Boolean,
) {
    fun install(quickJs: QuickJs) {
        quickJs.define("__mornye_file") {
            function("exists", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    resolve(args.getOrNull(0)?.toString() ?: "").isFile.toString()
            })
            function("read", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val file = resolve(args.getOrNull(0)?.toString() ?: "")
                    require(file.isFile) { "File does not exist" }
                    require(file.length() <= MAX_TEXT_BYTES) { "File is too large to read as text" }
                    return file.readText(Charsets.UTF_8)
                }
            })
            function("write", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val file = resolve(args.getOrNull(0)?.toString() ?: "")
                    val content = args.getOrNull(1)?.toString() ?: ""
                    require(content.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "File is too large" }
                    file.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create extension folder") }
                    file.writeText(content, Charsets.UTF_8)
                    return "true"
                }
            })
            function("delete", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val file = resolve(args.getOrNull(0)?.toString() ?: "")
                    return (!file.exists() || file.delete()).toString()
                }
            })
        }
    }

    private fun resolve(path: String): File {
        check(enabled) { "Permission denied: extension does not have file permission" }
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
            "Absolute file paths are not allowed"
        }
        require(!path.contains('\\') && !path.contains('\u0000')) { "Invalid file path" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Path traversal is not allowed" }
        val base = root.canonicalFile
        val target = File(base, parts.joinToString(File.separator)).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "File path is outside extension storage" }
        return target
    }

    private companion object {
        const val MAX_TEXT_BYTES = 10 * 1024 * 1024
    }
}
