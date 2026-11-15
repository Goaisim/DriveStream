package com.drake.drivestream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedLink(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val urlString: String,
    val dateAdded: Long = System.currentTimeMillis()
) {
    val displayDomain: String
        get() {
            val host = Uri.parse(urlString).host?.lowercase().orEmpty()
            if (host.isEmpty()) return urlString
            return if (host.startsWith("www.")) host.removePrefix("www.") else host
        }
}

class SavedLinksManager private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("drivestream_links", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _links = MutableStateFlow<List<SavedLink>>(emptyList())
    val links: StateFlow<List<SavedLink>> = _links.asStateFlow()

    init {
        load()
    }

    fun addLink(title: String, urlString: String): SavedLink? {
        val normalized = normalizedUrlString(urlString)
        val uri = validHttpUri(normalized) ?: return null
        val resolvedTitle = resolvedTitle(title, uri)
        val newLink = SavedLink(
            title = resolvedTitle,
            urlString = uri.toString()
        )
        synchronized(lock) {
            val updated = listOf(newLink) + _links.value
            _links.value = updated
            persistLocked(updated)
        }
        return newLink
    }

    fun updateLink(id: String, title: String, urlString: String): SavedLink? {
        val normalized = normalizedUrlString(urlString)
        val uri = validHttpUri(normalized) ?: return null
        val resolvedTitle = resolvedTitle(title, uri)
        var result: SavedLink? = null
        synchronized(lock) {
            val updated = _links.value.map { item ->
                if (item.id == id) {
                    item.copy(title = resolvedTitle, urlString = uri.toString()).also { result = it }
                } else {
                    item
                }
            }
            if (result != null) {
                _links.value = updated
                persistLocked(updated)
            }
        }
        return result
    }

    fun deleteLink(id: String) {
        synchronized(lock) {
            val updated = _links.value.filterNot { it.id == id }
            _links.value = updated
            persistLocked(updated)
        }
    }

    private fun resolvedTitle(rawTitle: String, uri: Uri): String {
        val trimmed = rawTitle.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val host = uri.host?.lowercase().orEmpty()
        return if (host.startsWith("www.")) host.removePrefix("www.") else host.ifEmpty { uri.toString() }
    }

    private fun load() {
        val raw = prefs.getString(STORAGE_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            val loaded = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = obj.optString("title")
                    val urlString = obj.optString("urlString")
                    val dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                    if (id.isNotEmpty() && urlString.isNotEmpty()) {
                        add(SavedLink(id = id, title = title, urlString = urlString, dateAdded = dateAdded))
                    }
                }
            }
            _links.value = loaded
        } catch (_: Exception) {
            _links.value = emptyList()
        }
    }

    private fun persistLocked(items: List<SavedLink>) {
        val arr = JSONArray()
        items.forEach { link ->
            arr.put(
                JSONObject().apply {
                    put("id", link.id)
                    put("title", link.title)
                    put("urlString", link.urlString)
                    put("dateAdded", link.dateAdded)
                }
            )
        }
        prefs.edit().putString(STORAGE_KEY, arr.toString()).apply()
    }

    companion object {
        private const val STORAGE_KEY = "saved_web_links"

        @Volatile
        private var instance: SavedLinksManager? = null

        fun getInstance(context: Context): SavedLinksManager {
            return instance ?: synchronized(this) {
                instance ?: SavedLinksManager(context).also { instance = it }
            }
        }

        fun normalizedUrlString(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val lower = trimmed.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") -> trimmed
                trimmed.startsWith("//") -> "https:$trimmed"
                else -> "https://$trimmed"
            }
        }

        fun validHttpUri(raw: String): Uri? {
            val normalized = normalizedUrlString(raw)
            if (normalized.isEmpty()) return null
            val uri = Uri.parse(normalized)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
                return null
            }
            return uri
        }
    }
}
