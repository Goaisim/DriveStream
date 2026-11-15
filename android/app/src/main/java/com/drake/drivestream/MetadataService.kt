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
    val episode: Int?,
    val year: Int? = null,
    val isMovie: Boolean = false
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
    data object IMDb : CoverSource()

    val label: String
        get() = when (this) {
            is TvMaze -> "TVMaze"
            is ITunes -> "iTunes"
            is IMDb -> "IMDb"
        }
}

data class CoverSearchResult(
    val id: String,
    val source: CoverSource,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val summary: String?,
    val isMovie: Boolean = false
) {
    val kindLabel: String
        get() {
            val base = if (isMovie) "Movie" else "TV Series"
            return if (!year.isNullOrBlank()) "$base ($year)" else base
        }
}

data class CustomCoverMatch(
    val posterUrl: String?,
    val tvmazeShowId: Int?,
    val updatedAt: Double? = null
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

    fun applySyncedEntries(merged: Map<String, PlaybackProgress>): Boolean {
        synchronized(lock) {
            val current = _progressByFileId.value
            val updated = current.toMutableMap()
            val ids = (prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()).toMutableSet()
            val editor = prefs.edit()
            var changed = false

            for ((fileId, entry) in merged) {
                val existing = current[fileId]
                val sameTime = existing != null &&
                    kotlin.math.abs(existing.updatedAtMs - entry.updatedAtMs) <= 2L &&
                    kotlin.math.abs(existing.position - entry.position) <= 0.01 &&
                    kotlin.math.abs(existing.duration - entry.duration) <= 0.01
                if (sameTime) continue

                changed = true
                updated[fileId] = entry
                ids.add(fileId)
                val json = JSONObject().apply {
                    put("fileId", entry.fileId)
                    put("position", entry.position)
                    put("duration", entry.duration)
                    put("updatedAtMs", entry.updatedAtMs)
                }.toString()
                editor.putString(KEY_PREFIX + fileId, json)
            }

            if (changed) {
                _progressByFileId.value = updated
                editor.putStringSet(KEY_INDEX, ids).apply()
            }
            return changed
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
        val episodes: Map<String, CachedEpisode>,
        val updatedAtSec: Double? = null
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
            return ParsedMediaInfo(
                title = cleanedTitle,
                season = season,
                episode = episode,
                year = extractReleaseYear(rawPrefix).first,
                isMovie = false
            )
        }

        val (year, titleWithoutYear) = extractReleaseYear(baseName)
        var cleaned = cleanTitle(titleWithoutYear)
        if (cleaned.isEmpty()) cleaned = cleanTitle(baseName)
        if (isSeasonFolderName(cleaned)) cleaned = ""
        val resolved = if (cleaned.isEmpty()) (validFolderHint ?: baseName) else cleaned
        return ParsedMediaInfo(
            title = resolved,
            season = null,
            episode = null,
            year = year,
            isMovie = true
        )
    }

    suspend fun metadata(file: DriveFile): ShowMetadata {
        val parsed = parseFilename(file.name, file.folderName)
        val driveThumb = file.thumbnailLink
        val showKey = normalizedShowKey(parsed.title)

        val show = resolveShow(
            query = parsed.title,
            showKey = showKey,
            isMovie = parsed.isMovie,
            year = parsed.year
        )
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
        val (year, cleanQuery) = extractReleaseYear(trimmed)
        val titleQuery = cleanTitle(cleanQuery).ifEmpty { trimmed }

        val movieItunesDeferred = async(Dispatchers.IO) {
            searchITunes(titleQuery, isMovie = true, year = year)
        }
        val imdbDeferred = async(Dispatchers.IO) {
            searchIMDbSuggestions(titleQuery, year = year)
        }
        val tvMazeDeferred = async(Dispatchers.IO) {
            searchTvMaze(titleQuery)
        }
        val tvItunesDeferred = async(Dispatchers.IO) {
            searchITunes(titleQuery, isMovie = false, year = null)
        }
        val movieItunes = movieItunesDeferred.await()
        val imdb = imdbDeferred.await()
        val tvMaze = tvMazeDeferred.await()
        val tvItunes = tvItunesDeferred.await()
        if (year != null) {
            movieItunes + imdb + tvMaze + tvItunes
        } else {
            tvMaze + movieItunes + imdb + tvItunes
        }
    }

    suspend fun applyCoverSelection(selection: CoverSearchResult, showTitle: String) {
        val showKey = normalizedShowKey(showTitle)
        if (showKey.isEmpty()) return
        val nowSec = System.currentTimeMillis() / 1000.0

        val resolved: CachedShow? = when (val src = selection.source) {
            is CoverSource.TvMaze -> {
                val fetched = withContext(Dispatchers.IO) { fetchTvMazeShowById(src.showId) }
                fetched?.let {
                    val url = if (it.posterUrl == null) selection.posterUrl else it.posterUrl
                    it.copy(posterUrl = url, updatedAtSec = nowSec)
                }
            }

            is CoverSource.ITunes, is CoverSource.IMDb -> {
                val existing = synchronized(cacheLock) {
                    manualBindings[showKey] ?: showsCache[showKey]
                } ?: if (!selection.isMovie) {
                    withContext(Dispatchers.IO) { fetchTvMazeSingleShow(selection.title) }
                } else {
                    null
                }

                val base = existing ?: CachedShow(
                    tvMazeId = null,
                    name = selection.title.ifBlank { showTitle },
                    summary = selection.summary,
                    posterUrl = selection.posterUrl,
                    episodes = emptyMap()
                )
                base.copy(
                    tvMazeId = if (selection.isMovie) null else base.tvMazeId,
                    name = selection.title.ifBlank { base.name },
                    summary = selection.summary ?: base.summary,
                    posterUrl = selection.posterUrl ?: base.posterUrl,
                    updatedAtSec = nowSec
                )
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

    fun exportCustomCoversForSync(): Map<String, CustomCoverMatch> {
        synchronized(cacheLock) {
            val out = linkedMapOf<String, CustomCoverMatch>()
            for ((showKey, show) in manualBindings) {
                out[showKey] = CustomCoverMatch(
                    posterUrl = show.posterUrl,
                    tvmazeShowId = show.tvMazeId,
                    updatedAt = show.updatedAtSec
                )
            }
            return out
        }
    }

    suspend fun applyMergedCustomCovers(merged: Map<String, CustomCoverMatch>): Boolean {
        var changed = false
        for ((showKey, remote) in merged) {
            if (showKey.isEmpty()) continue
            val existing = synchronized(cacheLock) { manualBindings[showKey] }
            val sameId = existing?.tvMazeId == remote.tvmazeShowId
            val samePoster = existing?.posterUrl == remote.posterUrl
            if (existing != null && sameId && samePoster) {
                if ((existing.updatedAtSec ?: 0.0) < (remote.updatedAt ?: 0.0)) {
                    val bumped = existing.copy(updatedAtSec = remote.updatedAt)
                    synchronized(cacheLock) {
                        manualBindings[showKey] = bumped
                        showsCache[showKey] = bumped
                    }
                }
                continue
            }

            var bound: CachedShow? = null
            if (remote.tvmazeShowId != null) {
                val fetched = withContext(Dispatchers.IO) { fetchTvMazeShowById(remote.tvmazeShowId) }
                if (fetched != null) {
                    bound = fetched.copy(
                        posterUrl = remote.posterUrl ?: fetched.posterUrl,
                        updatedAtSec = remote.updatedAt ?: (System.currentTimeMillis() / 1000.0)
                    )
                }
            }

            if (bound == null) {
                val fallback = existing ?: synchronized(cacheLock) { showsCache[showKey] }
                bound = CachedShow(
                    tvMazeId = remote.tvmazeShowId ?: fallback?.tvMazeId,
                    name = fallback?.name ?: showKey,
                    summary = fallback?.summary,
                    posterUrl = remote.posterUrl ?: fallback?.posterUrl,
                    episodes = fallback?.episodes ?: emptyMap(),
                    updatedAtSec = remote.updatedAt ?: (System.currentTimeMillis() / 1000.0)
                )
            }

            synchronized(cacheLock) {
                manualBindings[showKey] = bound
                showsCache[showKey] = bound
            }
            changed = true
        }

        if (changed) {
            synchronized(cacheLock) { saveCacheLocked() }
        }
        return changed
    }

    fun clearCache() {
        synchronized(cacheLock) {
            showsCache.clear()
            manualBindings.clear()
            runCatching { cacheFile.delete() }
        }
    }

    private suspend fun resolveShow(
        query: String,
        showKey: String,
        isMovie: Boolean = false,
        year: Int? = null
    ): CachedShow? {
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
                if (isMovie) {
                    resolveMovie(query, year) ?: resolveTvShow(query)
                } else {
                    resolveTvShow(query) ?: resolveMovie(query, year)
                }
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

    private fun resolveTvShow(query: String): CachedShow? {
        var show = fetchTvMazeSingleShow(query)
        if (show?.posterUrl == null) {
            val itunes = searchITunes(query, isMovie = false)
            val first = itunes.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: itunes.firstOrNull()
            if (first?.posterUrl != null) {
                show = show?.copy(posterUrl = first.posterUrl) ?: CachedShow(
                    tvMazeId = null,
                    name = first.title.ifBlank { query },
                    summary = first.summary,
                    posterUrl = first.posterUrl,
                    episodes = emptyMap()
                )
            }
        }
        return show
    }

    private fun resolveMovie(query: String, year: Int?): CachedShow? {
        val itunes = searchITunes(query, isMovie = true, year = year)
        val yearStr = year?.toString()
        if (yearStr != null) {
            val exactItunes = itunes.firstOrNull { it.year == yearStr && !it.posterUrl.isNullOrBlank() }
            if (exactItunes != null) {
                return CachedShow(
                    tvMazeId = null,
                    name = exactItunes.title.ifBlank { query },
                    summary = exactItunes.summary,
                    posterUrl = exactItunes.posterUrl,
                    episodes = emptyMap()
                )
            }
        }

        val imdb = searchIMDbSuggestions(query, year = year)
        val imdbMovies = imdb.filter { it.isMovie }.ifEmpty { imdb }
        if (yearStr != null) {
            val exactImdb = imdbMovies.firstOrNull { it.year == yearStr && !it.posterUrl.isNullOrBlank() }
            if (exactImdb != null) {
                return CachedShow(
                    tvMazeId = null,
                    name = exactImdb.title.ifBlank { query },
                    summary = exactImdb.summary,
                    posterUrl = exactImdb.posterUrl,
                    episodes = emptyMap()
                )
            }
        }

        val best = pickBestMovieMatch(itunes, year) ?: pickBestMovieMatch(imdbMovies, year)
        if (best?.posterUrl != null) {
            return CachedShow(
                tvMazeId = null,
                name = best.title.ifBlank { query },
                summary = best.summary,
                posterUrl = best.posterUrl,
                episodes = emptyMap()
            )
        }
        return null
    }

    private fun pickBestMovieMatch(results: List<CoverSearchResult>, year: Int?): CoverSearchResult? {
        val withPoster = results.filter { !it.posterUrl.isNullOrBlank() }
        if (year != null) {
            val yearStr = year.toString()
            withPoster.firstOrNull { it.year == yearStr }?.let { return it }
        }
        return withPoster.firstOrNull() ?: results.firstOrNull()
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

    private fun searchITunes(query: String, isMovie: Boolean, year: Int? = null): List<CoverSearchResult> {
        val termWithYear = if (isMovie && year != null) "$query $year" else query
        val primary = executeITunesSearch(termWithYear, isMovie)
        if (primary.isNotEmpty() || termWithYear == query) return primary
        return executeITunesSearch(query, isMovie)
    }

    private fun executeITunesSearch(term: String, isMovie: Boolean): List<CoverSearchResult> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", if (isMovie) "movie" else "tvShow")
            .addQueryParameter("entity", if (isMovie) "movie" else "tvSeason")
            .addQueryParameter("limit", if (isMovie) "5" else "8")
            .build()
        val body = httpGetString(url.toString())
        val parsed = body?.let { parseITunesResults(it, term, isMovie, requireFeatureMovieKind = false) }.orEmpty()
        if (parsed.isNotEmpty() || !isMovie) return parsed

        // iTunes Search sometimes returns 0 for media=movie; retry general US catalog filtered to feature-movie.
        val fallbackUrl = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("country", "us")
            .addQueryParameter("limit", "15")
            .build()
        val fallbackBody = httpGetString(fallbackUrl.toString()) ?: return emptyList()
        return parseITunesResults(fallbackBody, term, isMovie = true, requireFeatureMovieKind = true)
    }

    private fun parseITunesResults(
        body: String,
        term: String,
        isMovie: Boolean,
        requireFeatureMovieKind: Boolean
    ): List<CoverSearchResult> {
        return runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    if (requireFeatureMovieKind && obj.optNonBlank("kind") != "feature-movie") continue
                    val rawArt = obj.optNonBlank("artworkUrl100") ?: obj.optNonBlank("artworkUrl60")
                    val highResArt = rawArt?.replace(Regex("""\d+x\d+bb"""), "600x900bb")
                    val year = obj.optNonBlank("releaseDate")?.take(4)
                    val title = if (isMovie) {
                        obj.optNonBlank("trackName") ?: obj.optNonBlank("collectionName") ?: term
                    } else {
                        obj.optNonBlank("collectionName") ?: obj.optNonBlank("artistName") ?: term
                    }
                    val itemId = if (isMovie && obj.has("trackId")) {
                        "movie-${obj.optLong("trackId")}"
                    } else if (obj.has("collectionId")) {
                        obj.optLong("collectionId").toString()
                    } else {
                        "${title}-${i}"
                    }
                    val runtimeMin = obj.optLong("trackTimeMillis", 0L).let { ms ->
                        if (ms > 60_000L) (ms / 60_000L).toInt() else null
                    }
                    val rawDesc = stripHtml(obj.optNonBlank("longDescription") ?: obj.optNonBlank("shortDescription"))
                    val summary = when {
                        runtimeMin != null && !rawDesc.isNullOrBlank() -> "${runtimeMin}m • $rawDesc"
                        runtimeMin != null -> "${runtimeMin}m"
                        else -> rawDesc
                    }
                    add(
                        CoverSearchResult(
                            id = "itunes-$itemId",
                            source = CoverSource.ITunes,
                            title = title,
                            year = year,
                            posterUrl = highResArt,
                            summary = summary,
                            isMovie = isMovie
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun searchIMDbSuggestions(query: String, year: Int? = null): List<CoverSearchResult> {
        val candidates = if (year != null) {
            listOf("$query $year", query)
        } else {
            listOf(query)
        }
        for (term in candidates) {
            val found = executeIMDbSuggestionQuery(term)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun executeIMDbSuggestionQuery(term: String): List<CoverSearchResult> {
        val slug = term.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
        val firstChar = slug.firstOrNull()?.takeIf { it in 'a'..'z' || it in '0'..'9' } ?: return emptyList()
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"
        val body = httpGetString(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("d") ?: return emptyList()
            buildList {
                val limit = minOf(arr.length(), 8)
                for (i in 0 until limit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optNonBlank("id") ?: continue
                    if (!id.startsWith("tt")) continue
                    val title = obj.optNonBlank("l") ?: continue
                    val imageUrl = obj.optJSONObject("i")?.optNonBlank("imageUrl")
                    val year = if (obj.has("y") && !obj.isNull("y")) obj.optInt("y").takeIf { it > 1800 }?.toString() else null
                    val qid = obj.optNonBlank("qid").orEmpty().lowercase(Locale.US)
                    val qText = obj.optNonBlank("q").orEmpty().lowercase(Locale.US)
                    val isMovie = qid != "tvseries" && qid != "tvminiseries" &&
                        !qText.contains("series")
                    val castOrType = obj.optNonBlank("s")
                    add(
                        CoverSearchResult(
                            id = "imdb-$id",
                            source = CoverSource.IMDb,
                            title = title,
                            year = year,
                            posterUrl = imageUrl,
                            summary = castOrType,
                            isMovie = isMovie
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

    private fun extractReleaseYear(raw: String): Pair<Int?, String> {
        val spaced = raw.replace('.', ' ').replace('_', ' ')
        val parenMatch = PAREN_YEAR_REGEX.findAll(spaced).lastOrNull()
        if (parenMatch != null) {
            val year = parenMatch.groupValues[1].toIntOrNull()
            val prefix = spaced.substring(0, parenMatch.range.first).trim()
            if (prefix.isNotEmpty()) {
                return year to prefix
            }
        }
        val bareMatch = BARE_YEAR_REGEX.findAll(spaced).lastOrNull { it.range.first > 0 }
        if (bareMatch != null) {
            val before = spaced.substring(0, bareMatch.range.first).trim()
            if (before.isNotEmpty()) {
                return bareMatch.groupValues[1].toIntOrNull() to before
            }
        }
        return null to spaced
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
            val updatedAtSec = if (showObj.has("updatedAt") && !showObj.isNull("updatedAt")) {
                showObj.optDouble("updatedAt").takeIf { !it.isNaN() }
            } else {
                null
            }
            target[k] = CachedShow(
                tvMazeId = if (showObj.has("tvMazeId") && !showObj.isNull("tvMazeId")) showObj.optInt("tvMazeId") else null,
                name = showObj.optNonBlank("name").orEmpty(),
                summary = showObj.optNonBlank("summary"),
                posterUrl = showObj.optNonBlank("posterUrl"),
                episodes = eps,
                updatedAtSec = updatedAtSec
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
                    if (show.updatedAtSec != null) {
                        put("updatedAt", show.updatedAtSec)
                    }
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

        private val PAREN_YEAR_REGEX = Regex("""\(((?:19|20)\d{2})\)""")
        private val BARE_YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")

        private val TITLE_STRIP_PATTERNS = listOf(
            Regex("""\[[^\]]*]"""),
            Regex("""\((?:19|20)\d{2}\)"""),
            Regex(
                """\b(2160p|1080p|720p|480p|4k|uhd|hdr|web-?dl|webrip|blu-?ray|brrip|bdrip|dvdrip|hdtv|x264|x265|hevc|h\.?264|h\.?265|aac|ac3|dts|atmos|remux|proper|repack|extended|unrated)\b.*""",
                RegexOption.IGNORE_CASE
            )
        )
    }
}
