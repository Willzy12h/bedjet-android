package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Release(val tag: String, val name: String, val pageUrl: String, val apkUrl: String?, val notes: String)

/** Reads GitHub's "latest release" answer. Fetching happens in the app; this part is pure so it can be tested. */
object UpdateChecker {
    fun latestUrl(repo: String): String = "https://api.github.com/repos/${repo.trim().trim('/')}/releases/latest"

    fun parseLatest(text: String): Release? {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val tag = obj.string("tag_name") ?: return null
        val apk = runCatching { obj["assets"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("browser_download_url") }
            .firstOrNull { it.endsWith(".apk") }
        return Release(tag, obj.string("name") ?: tag, obj.string("html_url").orEmpty(), apk, obj.string("body").orEmpty())
    }

    /** True when [tag] (e.g. `v1.2.0`) is a later version than [current]. Pre-release suffixes don't count as newer. */
    fun isNewer(tag: String, current: String): Boolean {
        if (tag.contains('-')) return false
        val a = numbers(tag)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String) =
        version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
