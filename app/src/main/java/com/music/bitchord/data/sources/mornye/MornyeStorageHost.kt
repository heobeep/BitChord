package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Per-extension JSON key/value storage, isolated from the installed package. */
internal class MornyeStorageHost(
    private val storageFile: File,
    private val enabled: Boolean,
) {
    private val lock = Any()

    fun install(quickJs: QuickJs) {
        quickJs.define("__mornye_storage") {
            function("get", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = synchronized(lock) {
                    requirePermission()
                    val key = args.getOrNull(0)?.toString() ?: ""
                    readStore().opt(key)?.let { value -> JSONArray().put(value).toString().let { json -> json.substring(1, json.length - 1) } } ?: "null"
                }
            })
            function("set", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = synchronized(lock) {
                    requirePermission()
                    val key = args.getOrNull(0)?.toString() ?: ""
                    require(key.isNotBlank() && key.length <= 256) { "Invalid storage key" }
                    val raw = args.getOrNull(1)?.toString() ?: "null"
                    val value = JSONTokener(raw).nextValue()
                    val store = readStore().put(key, value)
                    writeStore(store)
                    "true"
                }
            })
            function("remove", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = synchronized(lock) {
                    requirePermission()
                    val key = args.getOrNull(0)?.toString() ?: ""
                    writeStore(readStore().apply { remove(key) })
                    "true"
                }
            })
            function("clear", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = synchronized(lock) {
                    requirePermission()
                    writeStore(JSONObject())
                    "true"
                }
            })
        }
    }

    private fun requirePermission() {
        check(enabled) { "Permission denied: extension does not have storage permission" }
    }

    private fun readStore(): JSONObject {
        if (!storageFile.isFile) return JSONObject()
        return runCatching { JSONObject(storageFile.readText(Charsets.UTF_8)) }
            .getOrElse { throw IOException("Extension storage is unreadable") }
    }

    private fun writeStore(json: JSONObject) {
        val parent = storageFile.parentFile ?: throw IOException("Invalid extension storage path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create extension storage")
        val temp = File(parent, ".storage-\${System.nanoTime()}.tmp")
        try {
            temp.writeText(json.toString(), Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
