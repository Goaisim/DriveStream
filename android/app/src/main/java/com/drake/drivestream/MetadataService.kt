package com.drake.drivestream

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedMediaInfo(
    val title: String,
    val season: Int?,
    val episode: Int?
) {
    val isEpisode: Boolean
        get() = season != null && episode != null

    val episodeCode: String?
        get() {
            val s = season ?: return null
            val e = episode ?: return null
            return String.format(Locale.US, "S%02dE%02d", s, e)
        }
}

data class ShowMetadata(
    val title: String,
    val episodeTitle: String?,
    val overview: String?,
    val posterUrl: String?,
    val stillUrl: String?,
    val season: Int?,
    val episode: Int?
)

sealed class CoverSource {
    data class TvMaze(val showId: Int) : CoverSource()
    data object ITunes : CoverSource()

    val label: String
        get() = when (this) {
            is TvMaze -> "TVMaze"
            is ITunes -> "iTunes"
        }
}

data class CoverSearchResult(
    val id: String,
    val source: CoverSource,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val summary: String?
)

data class PlaybackProgress(
    val fileId: String,
    val position: Double,
    val duration: Double,
    val updatedAtMs: Long
) {
    val fraction: Double
        get() = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0) else 0.0

    val isInProgress: Boolean
        get() = position >= 5.0 && (duration <= 0.0 || fraction < 0.95)
}

class PlaybackStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("drivestream_playback", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _progressByFileId = MutableStateFlow<Map<String, PlaybackProgress>>(emptyMap())
    val progressByFileId: StateFlow<Map<String, PlaybackProgress>> = _progressByFileId.asStateFlow()

    init {
        loadAll()
    }

    fun progress(fileId: String): PlaybackProgress? = _progressByFileId.value[fileId]

    fun save(fileId: String, position: Double, duration: Double) {
        if (!position.isFinite() || position < 0.0) return
        val safeDuration = if (duration.isFinite() && duration > 0.0) duration else 0.0
        val entry = PlaybackProgress(
            fileId = fileId,
            position = position,
            duration = safeDuration,
            updatedAtMs = System.currentTimeMillis()
        )
        synchronized(lock) {
            val updated = _progressByFileId.value.toMutableMap()
            updated[fileId] = entry
            _progressByFileId.value = updated

            val json = JSONObject().apply {
                put("fileId", entry.fileId)
                put("position", entry.position)
                put("duration", entry.duration)
                put("updatedAtMs", entry.updatedAtMs)
            }.toString()
            val ids = (prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()).toMutableSet()
            ids.add(fileId)
            prefs.edit()
                .putString(KEY_PREFIX + fileId, json)
                .putStringSet(KEY_INDEX, ids)
                .apply()
        }
    }

    fun clearProgress(fileId: String) {
        synchronized(lock) {
            val updated = _progressByFileId.value.toMutableMap()
            updated.remove(fileId)
            _progressByFileId.value = updated

            val ids = (prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()).toMutableSet()
            ids.remove(fileId)
            prefs.edit()
                .remove(KEY_PREFIX + fileId)
                .putStringSet(KEY_INDEX, ids)
                .apply()
        }
    }

    private fun loadAll() {
        val ids = prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()
        val loaded = linkedMapOf<String, PlaybackProgress>()
        for (id in ids) {
            val raw = prefs.getString(KEY_PREFIX + id, null) ?: continue
            runCatching {
                val obj = JSONObject(raw)
                loaded[id] = PlaybackProgress(
                    fileId = obj.getString("fileId"),
                    position = obj.getDouble("position"),
                    duration = obj.getDouble("duration"),
                    updatedAtMs = obj.getLong("updatedAtMs")
                )
            }
        }
        _progressByFileId.value = loaded
    }

    companion object {
        private const val KEY_PREFIX = "playback_progress_"
        private const val KEY_INDEX = "playback_progress_ids"

        @Volatile
        private var instance: PlaybackStore? = null

        fun getInstance(context: Context): PlaybackStore {
            return instance ?: synchronized(this) {
                instance ?: PlaybackStore(context).also { instance = it }
            }
        }
    }
}

class MetadataService private constructor(context: Context) {

    private data class CachedEpisode(
        val name: String,
        val summary: String?,
        val stillUrl: String?
    )

    private data class CachedShow(
        val tvMazeId: Int?,
        val name: String,
        val summary: String?,
        val posterUrl: String?,
        val episodes: Map<String, CachedEpisode>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(context.applicationContext.cacheDir, "show_metadata_cache.json")
    private val cacheLock = Any()
    private val lookupMutex = Mutex()
    private val inFlightLookups = mutableMapOf<String, CompletableDeferred<CachedShow?>>()

    private val showsCache = mutableMapOf<String, CachedShow>()
    private val manualBindings = mutableMapOf<String, CachedShow>()

    init {
        loadCache()
    }

    fun normalizedShowKey(title: String): String {
        return title.lowercase(Locale.US)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun isSeasonOnlyTitle(title: String): Boolean {
        return isSeasonFolderName(title.trim())
    }

    fun parseFilename(filename: String, folderHint: String? = null): ParsedMediaInfo {
        val lastComponent = filename.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = lastComponent.lastIndexOf('.')
        val baseName = if (dotIndex > 0) lastComponent.substring(0, dotIndex) else lastComponent

        val validFolderHint = folderHint?.trim()?.let { trimmed ->
            if (trimmed.isEmpty() || isSeasonFolderName(trimmed)) null else cleanTitle(trimmed)
        }

        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(baseName) ?: continue
            val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            val rawPrefix = baseName.substring(0, match.range.first)
            var cleanedTitle = cleanTitle(rawPrefix)
            if (isSeasonFolderName(cleanedTitle)) cleanedTitle = ""
            if (cleanedTitle.isEmpty() && !validFolderHint.isNullOrEmpty()) {
                cleanedTitle = validFolderHint
            }
            if (cleanedTitle.isEmpty()) {
                cleanedTitle = cleanTitle(baseName)
            }
            return ParsedMediaInfo(title = cleanedTitle, season = season, episode = episode)
        }

        var cleaned = cleanTitle(baseName)
        if (isSeasonFolderName(cleaned)) cleaned = ""
        val resolved = if (cleaned.isEmpty()) (validFolderHint ?: baseName) else cleaned
        return ParsedMediaInfo(title = resolved, season = null, episode = null)
    }

    suspend fun metadata(file: DriveFile): ShowMetadata {
        val parsed = parseFilename(file.name, file.folderName)
        val driveThumb = file.thumbnailLink
        val showKey = normalizedShowKey(parsed.title)

        val show = resolveShow(parsed.title, showKey)
        if (show != null) {
            var episodeTitle = parsed.episodeCode
            var overview = show.summary
            var stillUrl = driveThumb ?: show.posterUrl

            if (parsed.season != null && parsed.episode != null) {
                val ep = show.episodes[episodeKey(parsed.season, parsed.episode)]
                if (ep != null) {
                    if (ep.name.isNotEmpty()) episodeTitle = ep.name
                    if (!ep.summary.isNullOrEmpty()) overview = ep.summary
                    if (!ep.stillUrl.isNullOrEmpty()) stillUrl = ep.stillUrl
                }
            }

            return ShowMetadata(
                title = show.name,
                episodeTitle = episodeTitle,
                overview = overview,
                posterUrl = show.posterUrl,
                stillUrl = stillUrl,
                season = parsed.season,
                episode = parsed.episode
            )
        }

        return ShowMetadata(
            title = parsed.title,
            episodeTitle = parsed.episodeCode,
            overview = null,
            posterUrl = null,
            stillUrl = driveThumb,
            season = parsed.season,
            episode = parsed.episode
        )
    }

    suspend fun searchShowCandidates(query: String): List<CoverSearchResult> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@coroutineScope emptyList()

        val tvMazeDeferred = async(Dispatchers.IO) { searchTvMaze(trimmed) }
        val iTunesDeferred = async(Dispatchers.IO) { searchITunes(trimmed) }
        tvMazeDeferred.await() + iTunesDeferred.await()
    }

    suspend fun applyCoverSelection(selection: CoverSearchResult, showTitle: String) {
        val showKey = normalizedShowKey(showTitle)
        if (showKey.isEmpty()) return

        val resolved: CachedShow? = when (val src = selection.source) {
            is CoverSource.TvMaze -> {
                val fetched = withContext(Dispatchers.IO) { fetchTvMazeShowById(src.showId) }
                fetched?.let {
                    if (it.posterUrl == null) it.copy(posterUrl = selection.posterUrl) else it
                }
            }

            is CoverSource.ITunes -> {
                val existing = synchronized(cacheLock) {
                    manualBindings[showKey] ?: showsCache[showKey]
                } ?: withContext(Dispatchers.IO) { fetchTvMazeSingleShow(selection.title) }

                val base = existing ?: CachedShow(
                    tvMazeId = null,
                    name = showTitle,
                    summary = selection.summary,
                    posterUrl = selection.posterUrl,
                    episodes = emptyMap()
                )
                if (selection.posterUrl != null) base.copy(posterUrl = selection.posterUrl) else base
            }
        }

        if (resolved != null) {
            synchronized(cacheLock) {
                manualBindings[showKey] = resolved
                showsCache[showKey] = resolved
                saveCacheLocked()
            }
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            showsCache.clear()
            manualBindings.clear()
            runCatching { cacheFile.delete() }
        }
    }

    private suspend fun resolveShow(query: String, showKey: String): CachedShow? {
        if (showKey.isEmpty()) return null
        synchronized(cacheLock) {
            manualBindings[showKey]?.let { return it }
            showsCache[showKey]?.let { return it }
        }

        val (deferred, isOwner) = lookupMutex.withLock {
            synchronized(cacheLock) {
                manualBindings[showKey]?.let { return it }
                showsCache[showKey]?.let { return it }
            }
            val existing = inFlightLookups[showKey]
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<CachedShow?>()
                inFlightLookups[showKey] = created
                created to true
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        return try {
            val resolved = withContext(Dispatchers.IO) {
                var show = fetchTvMazeSingleShow(query)
                if (show?.posterUrl == null) {
                    val itunes = searchITunes(query)
                    val firstArt = itunes.firstOrNull()?.posterUrl
                    if (firstArt != null) {
                        show = show?.copy(posterUrl = firstArt) ?: CachedShow(
                            tvMazeId = null,
                            name = query,
                            summary = itunes.firstOrNull()?.summary,
                            posterUrl = firstArt,
                            episodes = emptyMap()
                        )
                    }
                }
                show
            }
            if (resolved != null) {
                synchronized(cacheLock) {
                    showsCache[showKey] = resolved
                    saveCacheLocked()
                }
            }
            deferred.complete(resolved)
            resolved
        } catch (t: Throwable) {
            deferred.complete(null)
            null
        } finally {
            lookupMutex.withLock {
                if (inFlightLookups[showKey] === deferred) {
                    inFlightLookups.remove(showKey)
                }
            }
        }
    }

    private fun fetchTvMazeSingleShow(query: String): CachedShow? {
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("embed", "episodes")
            .build()
        val body = httpGetString(url.toString()) ?: return null
        return runCatching { parseTvMazeShow(JSONObject(body)) }.getOrNull()
    }

    private fun fetchTvMazeShowById(id: Int): CachedShow? {
        val url = "https://api.tvmaze.com/shows/$id".toHttpUrl().newBuilder()
            .addQueryParameter("embed", "episodes")
            .build()
        val body = httpGetString(url.toString()) ?: return null
        return runCatching { parseTvMazeShow(JSONObject(body)) }.getOrNull()
    }

    private fun searchTvMaze(query: String): List<CoverSearchResult> {
        val url = "https://api.tvmaze.com/search/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val body = httpGetString(url.toString()) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            buildList {
                val limit = minOf(arr.length(), 8)
                for (i in 0 until limit) {
                    val showObj = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
                    val id = showObj.getInt("id")
                    val name = showObj.getString("name")
                    val premiered = showObj.optNonBlank("premiered")?.take(4)
                    val imageObj = showObj.optJSONObject("image")
                    val poster = imageObj?.optNonBlank("original") ?: imageObj?.optNonBlank("medium")
                    val summary = stripHtml(showObj.optNonBlank("summary"))
                    add(
                        CoverSearchResult(
                            id = "tvmaze-$id",
                            source = CoverSource.TvMaze(id),
                            title = name,
                            year = premiered,
                            posterUrl = poster,
                            summary = summary
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun searchITunes(query: String): List<CoverSearchResult> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", "tvShow")
            .addQueryParameter("entity", "tvSeason")
            .addQueryParameter("limit", "8")
            .build()
        val body = httpGetString(url.toString()) ?: return emptyList()
        return runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    val rawArt = obj.optNonBlank("artworkUrl100") ?: obj.optNonBlank("artworkUrl60")
                    val highResArt = rawArt
                        ?.replace("100x100bb", "600x600bb")
                        ?.replace("60x60bb", "600x600bb")
                    val year = obj.optNonBlank("releaseDate")?.take(4)
                    val title = obj.optNonBlank("collectionName") ?: obj.optNonBlank("artistName") ?: query
                    val collectionId = if (obj.has("collectionId")) obj.optLong("collectionId").toString() else title
                    val summary = stripHtml(obj.optNonBlank("longDescription"))
                    add(
                        CoverSearchResult(
                            id = "itunes-$collectionId",
                            source = CoverSource.ITunes,
                            title = title,
                            year = year,
                            posterUrl = highResArt,
                            summary = summary
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTvMazeShow(obj: JSONObject): CachedShow {
        val episodesMap = linkedMapOf<String, CachedEpisode>()
        val epArray = obj.optJSONObject("_embedded")?.optJSONArray("episodes")
        if (epArray != null) {
            for (i in 0 until epArray.length()) {
                val ep = epArray.optJSONObject(i) ?: continue
                if (!ep.has("season") || ep.isNull("season") || !ep.has("number") || ep.isNull("number")) continue
                val season = ep.optInt("season")
                val number = ep.optInt("number")
                val imageObj = ep.optJSONObject("image")
                val still = imageObj?.optNonBlank("original") ?: imageObj?.optNonBlank("medium")
                episodesMap[episodeKey(season, number)] = CachedEpisode(
                    name = ep.optNonBlank("name").orEmpty(),
                    summary = stripHtml(ep.optNonBlank("summary")),
                    stillUrl = still
                )
            }
        }
        val imageObj = obj.optJSONObject("image")
        val poster = imageObj?.optNonBlank("original") ?: imageObj?.optNonBlank("medium")
        return CachedShow(
            tvMazeId = obj.optInt("id"),
            name = obj.getString("name"),
            summary = stripHtml(obj.optNonBlank("summary")),
            posterUrl = poster,
            episodes = episodesMap
        )
    }

    private fun httpGetString(url: String): String? {
        return runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun episodeKey(season: Int, episode: Int): String = "S${season}E${episode}"

    private fun isSeasonFolderName(name: String): Boolean {
        return SEASON_NAME_REGEX.matches(name)
    }

    private fun cleanTitle(raw: String): String {
        var text = raw.replace('.', ' ').replace('_', ' ')
        for (pattern in TITLE_STRIP_PATTERNS) {
            text = text.replace(pattern, "")
        }
        text = text.replace(Regex("""\s*[-–—:]+\s*$"""), "")
        text = text.replace(Regex("""\s+"""), " ")
        return text.trim()
    }

    private fun stripHtml(html: String?): String? {
        if (html.isNullOrEmpty()) return null
        val stripped = html
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
        return stripped.ifEmpty { null }
    }

    private fun loadCache() {
        if (!cacheFile.exists()) return
        runCatching {
            val root = JSONObject(cacheFile.readText())
            decodeShowMap(root.optJSONObject("shows"), showsCache)
            decodeShowMap(root.optJSONObject("manualBindings"), manualBindings)
        }
    }

    private fun decodeShowMap(json: JSONObject?, target: MutableMap<String, CachedShow>) {
        if (json == null) return
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val showObj = json.optJSONObject(k) ?: continue
            val epsJson = showObj.optJSONObject("episodes")
            val eps = linkedMapOf<String, CachedEpisode>()
            if (epsJson != null) {
                val epKeys = epsJson.keys()
                while (epKeys.hasNext()) {
                    val ek = epKeys.next()
                    val epObj = epsJson.optJSONObject(ek) ?: continue
                    eps[ek] = CachedEpisode(
                        name = epObj.optNonBlank("name").orEmpty(),
                        summary = epObj.optNonBlank("summary"),
                        stillUrl = epObj.optNonBlank("stillUrl")
                    )
                }
            }
            target[k] = CachedShow(
                tvMazeId = if (showObj.has("tvMazeId") && !showObj.isNull("tvMazeId")) showObj.optInt("tvMazeId") else null,
                name = showObj.optNonBlank("name").orEmpty(),
                summary = showObj.optNonBlank("summary"),
                posterUrl = showObj.optNonBlank("posterUrl"),
                episodes = eps
            )
        }
    }

    private fun saveCacheLocked() {
        runCatching {
            val root = JSONObject().apply {
                put("shows", encodeShowMap(showsCache))
                put("manualBindings", encodeShowMap(manualBindings))
            }
            cacheFile.writeText(root.toString())
        }
    }

    private fun encodeShowMap(source: Map<String, CachedShow>): JSONObject {
        val out = JSONObject()
        for ((k, show) in source) {
            val epsObj = JSONObject()
            for ((ek, ep) in show.episodes) {
                epsObj.put(
                    ek,
                    JSONObject().apply {
                        put("name", ep.name)
                        put("summary", ep.summary)
                        put("stillUrl", ep.stillUrl)
                    }
                )
            }
            out.put(
                k,
                JSONObject().apply {
                    put("tvMazeId", show.tvMazeId)
                    put("name", show.name)
                    put("summary", show.summary)
                    put("posterUrl", show.posterUrl)
                    put("episodes", epsObj)
                }
            )
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: MetadataService? = null

        fun getInstance(context: Context): MetadataService {
            return instance ?: synchronized(this) {
                instance ?: MetadataService(context).also { instance = it }
            }
        }

        private val EPISODE_PATTERNS = listOf(
            Regex("""(?<![A-Za-z0-9])S(\d{1,2})[.\s_-]?E(\d{1,3})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE),
            Regex("""(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE),
            Regex("""(?<![A-Za-z0-9])Season\s*(\d{1,2})\s*(?:Episode|Ep\.?)\s*(\d{1,3})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
        )

        private val SEASON_NAME_REGEX = Regex(
            """^(?:season(?:\s+.*|\d+)?|s\s*\d+|specials|extras)$""",
            RegexOption.IGNORE_CASE
        )

        private val TITLE_STRIP_PATTERNS = listOf(
            Regex("""\[[^\]]*]"""),
            Regex("""\((?:19|20)\d{2}\)"""),
            Regex(
                """\b(2160p|1080p|720p|480p|4k|hdr|web-?dl|webrip|blu-?ray|bdrip|dvdrip|hdtv|x264|x265|hevc|h\.?264|h\.?265|aac|dts|atmos)\b.*""",
                RegexOption.IGNORE_CASE
            )
        )
    }
}
