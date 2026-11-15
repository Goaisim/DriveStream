package com.drake.drivestream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val thumbnailLink: String?,
    val durationSeconds: Double?,
    val parents: List<String>,
    val shortcutTargetId: String?,
    val shortcutTargetMimeType: String?,
    val folderName: String? = null
) {
    val effectiveId: String
        get() = if (mimeType == "application/vnd.google-apps.shortcut" && !shortcutTargetId.isNullOrBlank()) {
            shortcutTargetId
        } else {
            id
        }

    val effectiveMimeType: String
        get() = if (mimeType == "application/vnd.google-apps.shortcut" && !shortcutTargetMimeType.isNullOrBlank()) {
            shortcutTargetMimeType
        } else {
            mimeType
        }

    val fileExtension: String
        get() {
            val dot = name.lastIndexOf('.')
            return if (dot >= 0 && dot + 1 < name.length) name.substring(dot + 1).lowercase(Locale.US) else ""
        }

    val isFolder: Boolean
        get() = effectiveMimeType == "application/vnd.google-apps.folder"

    val isSupportedVideo: Boolean
        get() {
            if (isFolder) return false
            if (effectiveMimeType.lowercase(Locale.US).startsWith("video/")) return true
            return fileExtension in VIDEO_EXTENSIONS
        }

    val formattedSize: String?
        get() {
            val bytes = sizeBytes ?: return null
            if (bytes <= 0L) return null
            val kb = 1000.0
            val mb = kb * 1000.0
            val gb = mb * 1000.0
            return when {
                bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
                else -> "$bytes B"
            }
        }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "avi", "webm", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "ogv"
        )
    }
}

class DriveSettings private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("drivestream_settings", Context.MODE_PRIVATE)

    private val _folderId = MutableStateFlow(loadInitialFolderId())
    val folderId: StateFlow<String> = _folderId.asStateFlow()

    private val _audioDelayMs = MutableStateFlow(
        prefs.getLong(KEY_AUDIO_DELAY_MS, DEFAULT_AUDIO_DELAY_MS)
            .coerceIn(MIN_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS)
    )
    val audioDelayMs: StateFlow<Long> = _audioDelayMs.asStateFlow()

    private fun loadInitialFolderId(): String {
        if (!prefs.contains(KEY_FOLDER_ID)) {
            return DriveService.DEFAULT_FOLDER_ID
        }
        return prefs.getString(KEY_FOLDER_ID, "") ?: ""
    }

    fun setFolderId(raw: String) {
        val trimmed = raw.trim()
        prefs.edit().putString(KEY_FOLDER_ID, trimmed).apply()
        _folderId.value = trimmed
    }

    fun setAudioDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(MIN_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS)
        prefs.edit().putLong(KEY_AUDIO_DELAY_MS, clamped).apply()
        _audioDelayMs.value = clamped
    }

    companion object {
        const val DEFAULT_AUDIO_DELAY_MS = -200L
        const val MIN_AUDIO_DELAY_MS = -500L
        const val MAX_AUDIO_DELAY_MS = 500L
        private const val KEY_FOLDER_ID = "drive_folder_id"
        private const val KEY_AUDIO_DELAY_MS = "audio_delay_ms"

        @Volatile
        private var instance: DriveSettings? = null

        fun getInstance(context: Context): DriveSettings {
            return instance ?: synchronized(this) {
                instance ?: DriveSettings(context).also { instance = it }
            }
        }
    }
}

class DriveService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class DriveNodeMetadata(
        val id: String,
        val name: String,
        val parents: List<String>
    )

    private data class DriveFilePage(
        val nextPageToken: String?,
        val files: List<DriveFile>
    )

    suspend fun fetchVideoFiles(folderId: String?, authManager: AuthManager): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val trimmedFolder = folderId?.trim().orEmpty()
            if (trimmedFolder.isEmpty()) {
                val mediaFilter = "mimeType != 'application/vnd.google-apps.folder' and " +
                    "(mimeType contains 'video/' or name contains '.mp4' or name contains '.m4v' or " +
                    "name contains '.mov' or name contains '.mkv' or name contains '.avi')"
                val query = "trashed = false and $mediaFilter"
                val videos = listAllPages(query = query, folderName = null, authManager = authManager)
                    .filter { it.isSupportedVideo }
                return@withContext populateMissingShowHints(videos, authManager)
            }

            val initialHint = resolveShowHint(trimmedFolder, authManager)

            val allVideos = mutableListOf<DriveFile>()
            val visitedFolders = hashSetOf(trimmedFolder)
            val queue = ArrayDeque<Pair<String, String?>>()
            queue.addLast(trimmedFolder to initialHint)

            while (queue.isNotEmpty()) {
                val (currentId, currentHint) = queue.removeFirst()
                val escapedId = currentId.replace("'", "\\'")
                val childrenQuery = "'$escapedId' in parents and trashed = false"
                val children = listAllPages(childrenQuery, currentHint, authManager)

                for (child in children) {
                    if (child.isFolder) {
                        val targetFolderId = child.effectiveId
                        if (!visitedFolders.add(targetFolderId)) continue
                        val nextHint = if (isSeasonFolder(child.name)) currentHint else child.name
                        queue.addLast(targetFolderId to nextHint)
                    } else if (child.isSupportedVideo) {
                        allVideos.add(child.copy(folderName = currentHint))
                    }
                }
            }

            allVideos
        }

    private fun isSeasonFolder(name: String): Boolean {
        return SEASON_FOLDER_REGEX.matches(name.trim())
    }

    private fun isGenericRootFolder(name: String): Boolean {
        return name.trim().lowercase(Locale.US) in GENERIC_ROOTS
    }

    private suspend fun resolveShowHint(folderId: String, authManager: AuthManager): String? {
        val cache = hashMapOf<String, DriveNodeMetadata>()
        return resolveNonSeasonAncestor(folderId, cache, authManager)
    }

    private suspend fun populateMissingShowHints(
        files: List<DriveFile>,
        authManager: AuthManager
    ): List<DriveFile> {
        val cache = hashMapOf<String, DriveNodeMetadata>()
        return files.map { file ->
            if (file.folderName != null || file.parents.isEmpty()) {
                file
            } else {
                val hint = resolveNonSeasonAncestor(file.parents.first(), cache, authManager)
                file.copy(folderName = hint)
            }
        }
    }

    private suspend fun resolveNonSeasonAncestor(
        folderId: String,
        cache: MutableMap<String, DriveNodeMetadata>,
        authManager: AuthManager
    ): String? {
        var currentId: String? = folderId
        var depth = 0
        while (currentId != null && depth < 4) {
            depth += 1
            val meta = cache[currentId] ?: runCatching {
                fetchFolderMetadata(currentId!!, authManager)
            }.getOrNull()?.also { cache[currentId!!] = it } ?: return null

            if (!isSeasonFolder(meta.name)) {
                return if (isGenericRootFolder(meta.name)) null else meta.name
            }
            currentId = meta.parents.firstOrNull()
        }
        return null
    }

    private suspend fun fetchFolderMetadata(id: String, authManager: AuthManager): DriveNodeMetadata {
        val url = "https://www.googleapis.com/drive/v3/files/$id?fields=id,name,parents&supportsAllDrives=true"
        val json = executeAuthedJson(url, authManager)
        val parentsJson = json.optJSONArray("parents")
        val parents = buildList {
            if (parentsJson != null) {
                for (i in 0 until parentsJson.length()) {
                    add(parentsJson.getString(i))
                }
            }
        }
        return DriveNodeMetadata(
            id = json.getString("id"),
            name = json.getString("name"),
            parents = parents
        )
    }

    private suspend fun listAllPages(
        query: String,
        folderName: String?,
        authManager: AuthManager
    ): List<DriveFile> {
        val collected = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val page = fetchPage(query, pageToken, authManager)
            for (file in page.files) {
                collected.add(file.copy(folderName = folderName))
            }
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        return collected
    }

    private suspend fun fetchPage(
        query: String,
        pageToken: String?,
        authManager: AuthManager
    ): DriveFilePage {
        val urlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter(
                "fields",
                "nextPageToken,files(id,name,mimeType,size,thumbnailLink,videoMediaMetadata,parents,shortcutDetails)"
            )
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
        if (!pageToken.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("pageToken", pageToken)
        }

        val json = executeAuthedJson(urlBuilder.build().toString(), authManager)
        val nextToken = json.optNonBlank("nextPageToken")
        val filesArr = json.optJSONArray("files")
        val files = buildList {
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val obj = filesArr.optJSONObject(i) ?: continue
                    add(parseDriveFile(obj))
                }
            }
        }
        return DriveFilePage(nextPageToken = nextToken, files = files)
    }

    private suspend fun executeAuthedJson(url: String, authManager: AuthManager): JSONObject {
        val token = authManager.validAccessToken()
        val first = runGet(url, token)
        if (first.first == 401) {
            val refreshed = authManager.refreshAccessToken()
            val retry = runGet(url, refreshed)
            if (retry.first !in 200..299) {
                throw IOException("Google Drive error (${retry.first}): ${retry.second}")
            }
            return JSONObject(retry.second)
        }
        if (first.first !in 200..299) {
            throw IOException("Google Drive error (${first.first}): ${first.second}")
        }
        return JSONObject(first.second)
    }

    private fun runGet(url: String, accessToken: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun parseDriveFile(obj: JSONObject): DriveFile {
        val sizeStr = obj.optString("size", "")
        val sizeBytes = sizeStr.toLongOrNull()

        val videoMeta = obj.optJSONObject("videoMediaMetadata")
        val durationMillis = videoMeta?.optString("durationMillis", "")?.toDoubleOrNull()

        val parentsArr = obj.optJSONArray("parents")
        val parents = buildList {
            if (parentsArr != null) {
                for (i in 0 until parentsArr.length()) {
                    add(parentsArr.getString(i))
                }
            }
        }

        val shortcut = obj.optJSONObject("shortcutDetails")
        return DriveFile(
            id = obj.getString("id"),
            name = obj.getString("name"),
            mimeType = obj.getString("mimeType"),
            sizeBytes = sizeBytes,
            thumbnailLink = obj.optNonBlank("thumbnailLink"),
            durationSeconds = durationMillis?.div(1000.0),
            parents = parents,
            shortcutTargetId = shortcut?.optNonBlank("targetId"),
            shortcutTargetMimeType = shortcut?.optNonBlank("targetMimeType")
        )
    }

    companion object {
        const val DEFAULT_FOLDER_ID = "89VXpiNB2PxwnR__gNUwAuwU2wnsT1ME"

        private val SEASON_FOLDER_REGEX = Regex(
            """^(?:season(?:\s+.*|\d+)?|s\s*\d+|specials|extras)$""",
            RegexOption.IGNORE_CASE
        )

        private val GENERIC_ROOTS = setOf(
            "my drive", "drive", "tv", "tv shows", "shows", "series", "videos", "movies", "media"
        )
    }
}

internal fun JSONObject.optNonBlank(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key, "")
    return if (value.isBlank()) null else value
}
