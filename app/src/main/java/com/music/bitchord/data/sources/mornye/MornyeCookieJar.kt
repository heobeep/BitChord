package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.music.bitchord.auth.EncryptedPrefs
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-extension persistent HTTP cookie jar.
 *
 * Cookie values are stored in BitChord's encrypted preferences, never shared
 * between extensions, and are restored with their original domain/path flags.
 */
internal class MornyeCookieJar(
    context: Context,
    extensionId: String,
) : CookieJar {
    private val prefs = EncryptedPrefs.open(
        context.applicationContext,
        "bitchord_mornye_cookies",
        "bitchord_mornye_cookies_plain",
    )
    private val key = "extension." + extensionId

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val current = readCookies().associateByTo(linkedMapOf(), ::identity)
        val now = System.currentTimeMillis()
        current.entries.removeAll { it.value.expiresAt <= now }
        cookies.forEach { cookie ->
            val id = identity(cookie)
            if (cookie.expiresAt <= now) current.remove(id) else current[id] = cookie
        }
        writeCookies(current.values.toList())
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val cookies = readCookies()
        val valid = cookies.filter { it.expiresAt > now }
        if (valid.size != cookies.size) writeCookies(valid)
        return valid.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(key).apply()
    }

    private fun readCookies(): List<Cookie> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                val value = item.optString("value")
                val domain = item.optString("domain")
                val path = item.optString("path", "/")
                if (name.isBlank() || domain.isBlank()) return@mapNotNull null
                val builder = Cookie.Builder().name(name).value(value).path(path)
                if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(domain)
                else builder.domain(domain)
                if (item.optBoolean("persistent")) {
                    builder.expiresAt(item.optLong("expiresAt", Long.MAX_VALUE))
                }
                if (item.optBoolean("secure")) builder.secure()
                if (item.optBoolean("httpOnly")) builder.httpOnly()
                builder.build()
            }
        }.getOrElse {
            prefs.edit().remove(key).apply()
            emptyList()
        }
    }

    private fun writeCookies(cookies: List<Cookie>) {
        val array = JSONArray()
        cookies.forEach { cookie ->
            array.put(
                JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("hostOnly", cookie.hostOnly)
                    .put("persistent", cookie.persistent)
                    .put("expiresAt", cookie.expiresAt)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly),
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun identity(cookie: Cookie): String =
        cookie.name + "|" + cookie.domain + "|" + cookie.path
}
