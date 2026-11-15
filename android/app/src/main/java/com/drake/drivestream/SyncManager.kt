package com.drake.drivestream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToLong

data class DriveStreamSyncState(
    val version: Int = 1,
    val savedLinks: List<SyncedSavedLink> = emptyList(),
    val deletedLinkIds: Map<String, Double> = emptyMap(),
    val watchProgress: Map<String, WatchProgressEntry> = emptyMap(),
    val customCovers: Map<String, CustomCoverMatch> = emptyMap()
) {
    data class SyncedSavedLink(
        val id: String,
        val title: String,
        val urlString: String,
        val dateAdded: Double
    )

    data class WatchProgressEntry(
        val positionSeconds: Double,
        val durationSeconds: Double,
        val updatedAt: Double
    )
}

class SyncManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("drivestream_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val peerClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(750, TimeUnit.MILLISECONDS)
        .callTimeout(950, TimeUnit.MILLISECONDS)
        .build()

    private val _peerHost = MutableStateFlow(cleanHost(prefs.getString(KEY_PEER_HOST, "").orEmpty()))
    val peerHost: StateFlow<String> = _peerHost.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _coversRevision = MutableStateFlow(0)
    val coversRevision: StateFlow<Int> = _coversRevision.asStateFlow()

    @Volatile
    private var cachedAppDataFileId: String? = null
    @Volatile
    private var isApplyingRemote = false
    private var debounceJob: Job? = null

    fun setPeerHost(rawHost: String) {
        val cleaned = cleanHost(rawHost)
        _peerHost.value = cleaned
        prefs.edit().putString(KEY_PEER_HOST, cleaned).apply()
    }

    fun resetCloudStateOnSignOut() {
        cachedAppDataFileId = null
    }

    fun scheduleSyncAfterChange() {
        if (isApplyingRemote) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(350L)
            syncAll(includePeer = true, includeCloud = true)
        }
    }

    fun scheduleCloudSyncAfterServerMutation() {
        scope.launch {
            delay(350L)
            syncAll(includePeer = false, includeCloud = true)
        }
    }

    fun syncOnLaunch() {
        scope.launch {
            syncAll(includePeer = _peerHost.value.isNotBlank(), includeCloud = true)
        }
    }

    fun triggerManualSync() {
        scope.launch {
            syncAll(includePeer = _peerHost.value.isNotBlank(), includeCloud = true)
        }
    }

    fun scanLocalNetworkForPeer() {
        if (_isScanning.value) return
        scope.launch {
            _isScanning.value = true
            _statusMessage.value = "Scanning local Wi-Fi (port 8766)…"
            try {
                val found = discoverFirstPeerHost()
                if (found != null) {
                    setPeerHost(found)
                    _statusMessage.value = "Found peer at $found"
                    syncAll(includePeer = true, includeCloud = true)
                } else {
                    _statusMessage.value = "No peer found on Wi-Fi"
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    suspend fun receivePeerSyncJson(rawJson: String): String {
        val remoteState = decodeSyncState(rawJson)
            ?: throw IllegalArgumentException("Invalid DriveStreamSyncState JSON")
        val merged = syncMutex.withLock {
            val local = exportLocalState()
            val combined = mergeStates(local, remoteState)
            applyMergedState(combined)
            combined
        }
        scheduleCloudSyncAfterServerMutation()
        return encodeSyncState(merged)
    }

    fun exportLocalStateJson(): String = encodeSyncState(exportLocalState())

    private suspend fun syncAll(includePeer: Boolean, includeCloud: Boolean) {
        syncMutex.withLock {
            _isSyncing.value = true
            val notes = mutableListOf<String>()
            try {
                val host = _peerHost.value
                if (includePeer && host.isNotBlank()) {
                    val ok = syncWithPeer(host)
                    notes.add(if (ok) "Wi-Fi OK" else "Wi-Fi offline")
                }

                val authManager = AuthManager.getInstance(appContext)
                if (includeCloud && authManager.isAuthenticated.value && authManager.hasAppDataScope.value) {
                    val ok = syncWithGoogleDriveAppData(authManager)
                    notes.add(if (ok) "Drive Cloud OK" else "Drive Cloud error")
                }

                _statusMessage.value = when {
                    notes.isNotEmpty() -> notes.joinToString(" • ")
                    else -> {
                        val am = AuthManager.getInstance(appContext)
                        if (am.isAuthenticated.value && !am.hasAppDataScope.value) {
                            "Enable Cloud Sync or connect from iPhone on Wi-Fi"
                        } else {
                            "Ready"
                        }
                    }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // MARK: - Export & Apply

    fun exportLocalState(): DriveStreamSyncState {
        val linksMgr = SavedLinksManager.getInstance(appContext)
        val playbackStore = PlaybackStore.getInstance(appContext)
        val metadataService = MetadataService.getInstance(appContext)

        val (links, tombsMs) = linksMgr.snapshotForSync()
        val syncedLinks = links.map { link ->
            DriveStreamSyncState.SyncedSavedLink(
                id = link.id.lowercase(),
                title = link.title,
                urlString = link.urlString,
                dateAdded = link.dateAdded / 1000.0
            )
        }
        val deletedSec = tombsMs.mapValues { (_, ms) -> ms / 1000.0 }

        val watchProgress = linkedMapOf<String, DriveStreamSyncState.WatchProgressEntry>()
        for ((fileId, entry) in playbackStore.progressByFileId.value) {
            watchProgress[fileId] = DriveStreamSyncState.WatchProgressEntry(
                positionSeconds = entry.position,
                durationSeconds = entry.duration,
                updatedAt = entry.updatedAtMs / 1000.0
            )
        }

        val customCovers = metadataService.exportCustomCoversForSync()

        return DriveStreamSyncState(
            version = 1,
            savedLinks = syncedLinks,
            deletedLinkIds = deletedSec,
            watchProgress = watchProgress,
            customCovers = customCovers
        )
    }

    private suspend fun applyMergedState(merged: DriveStreamSyncState) {
        isApplyingRemote = true
        try {
            val linksMgr = SavedLinksManager.getInstance(appContext)
            val playbackStore = PlaybackStore.getInstance(appContext)
            val metadataService = MetadataService.getInstance(appContext)

            val convertedLinks = merged.savedLinks.map { item ->
                SavedLink(
                    id = item.id.lowercase(),
                    title = item.title,
                    urlString = item.urlString,
                    dateAdded = (item.dateAdded * 1000.0).roundToLong()
                )
            }
            val convertedTombsMs = merged.deletedLinkIds.mapValues { (_, sec) ->
                (sec * 1000.0).roundToLong()
            }
            linksMgr.applySyncedState(convertedLinks, convertedTombsMs)

            val convertedProgress = linkedMapOf<String, PlaybackProgress>()
            for ((fileId, entry) in merged.watchProgress) {
                convertedProgress[fileId] = PlaybackProgress(
                    fileId = fileId,
                    position = entry.positionSeconds,
                    duration = entry.durationSeconds,
                    updatedAtMs = (entry.updatedAt * 1000.0).roundToLong()
                )
            }
            playbackStore.applySyncedEntries(convertedProgress)

            val coversChanged = metadataService.applyMergedCustomCovers(merged.customCovers)
            if (coversChanged) {
                _coversRevision.value += 1
            }
        } finally {
            isApplyingRemote = false
        }
    }

    // MARK: - Channel A: Home Wi-Fi Peer Sync

    private suspend fun syncWithPeer(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = encodeSyncState(exportLocalState())
            val req = Request.Builder()
                .url("http://$host:${AuthManager.COMPANION_PORT}/api/sync")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            peerClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val body = resp.body?.string().orEmpty()
                val remoteMerged = decodeSyncState(body) ?: return@runCatching false
                val merged = mergeStates(exportLocalState(), remoteMerged)
                applyMergedState(merged)
                true
            }
        }.getOrDefault(false)
    }

    private suspend fun discoverFirstPeerHost(): String? = withContext(Dispatchers.IO) {
        val localIp = AuthManager.detectLocalIpAddress()
        val parts = localIp.split(".")
        if (parts.size != 4 || localIp == "127.0.0.1") return@withContext null
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"

        val candidates = (1..254).mapNotNull { hostPart ->
            val ip = "$prefix.$hostPart"
            if (ip == localIp) null else ip
        }

        val winner = AtomicReference<String?>(null)
        val semaphore = Semaphore(48)

        coroutineScope {
            candidates.map { ip ->
                async {
                    if (winner.get() != null) return@async
                    semaphore.withPermit {
                        if (winner.get() != null) return@withPermit
                        val req = Request.Builder()
                            .url("http://$ip:${AuthManager.COMPANION_PORT}/api/sync")
                            .get()
                            .build()
                        val matched = runCatching {
                            scanClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@runCatching false
                                val body = resp.body?.string().orEmpty()
                                decodeSyncState(body)?.let { it.version >= 1 } == true
                            }
                        }.getOrDefault(false)
                        if (matched) {
                            winner.compareAndSet(null, ip)
                        }
                    }
                }
            }.awaitAll()
        }
        winner.get()
    }

    // MARK: - Channel B: Google Drive Cloud Sync (appDataFolder)

    private suspend fun syncWithGoogleDriveAppData(authManager: AuthManager): Boolean = withContext(Dispatchers.IO) {
        try {
            var token = authManager.validAccessToken()
            var fileId = cachedAppDataFileId
            if (fileId == null) {
                val (foundId, updatedToken) = findCloudSyncFileId(authManager, token)
                fileId = foundId
                cachedAppDataFileId = foundId
                token = updatedToken
            }

            var remoteState: DriveStreamSyncState? = null
            if (fileId != null) {
                val (downloaded, status, updatedToken) = downloadCloudSyncFile(authManager, fileId, token)
                token = updatedToken
                if (status == 404) {
                    cachedAppDataFileId = null
                    fileId = null
                } else {
                    remoteState = downloaded
                }
            }

            val localState = exportLocalState()
            val mergedState: DriveStreamSyncState
            val needsUpload: Boolean

            if (remoteState != null) {
                mergedState = mergeStates(localState, remoteState)
                needsUpload = mergedState != remoteState
            } else {
                mergedState = mergeStates(localState, DriveStreamSyncState())
                needsUpload = true
            }

            applyMergedState(mergedState)

            if (needsUpload) {
                val latestMerged = mergeStates(exportLocalState(), mergedState)
                val payload = encodeSyncState(latestMerged)
                if (fileId != null) {
                    val ok = updateCloudSyncFile(authManager, fileId, payload, token)
                    if (!ok) cachedAppDataFileId = null
                } else {
                    cachedAppDataFileId = createCloudSyncFile(authManager, payload, token)
                }
            }
            true
        } catch (_: InsufficientScopeException) {
            authManager.markAppDataScopeRevoked()
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun findCloudSyncFileId(
        authManager: AuthManager,
        token: String
    ): Pair<String?, String> {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name='$CLOUD_FILE_NAME' and trashed=false")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "1")
            .build()

        val req = Request.Builder().url(url).get().build()
        val (code, body, activeToken) = executeAuthedDriveRequest(authManager, req, token)
        if (code !in 200..299) throw IOException("Drive appData list error ($code)")
        val obj = JSONObject(body)
        val first = obj.optJSONArray("files")?.optJSONObject(0)
        return first?.optString("id")?.takeIf { it.isNotBlank() } to activeToken
    }

    private suspend fun downloadCloudSyncFile(
        authManager: AuthManager,
        fileId: String,
        token: String
    ): Triple<DriveStreamSyncState?, Int, String> {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val req = Request.Builder().url(url).get().build()
        val (code, body, activeToken) = executeAuthedDriveRequest(authManager, req, token)
        if (code == 404) return Triple(null, 404, activeToken)
        if (code !in 200..299) throw IOException("Drive appData download error ($code)")
        return Triple(decodeSyncState(body), code, activeToken)
    }

    private suspend fun updateCloudSyncFile(
        authManager: AuthManager,
        fileId: String,
        payload: String,
        token: String
    ): Boolean {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        val req = Request.Builder()
            .url(url)
            .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, _, _) = executeAuthedDriveRequest(authManager, req, token)
        if (code == 404) return false
        if (code !in 200..299) throw IOException("Drive appData update error ($code)")
        return true
    }

    private suspend fun createCloudSyncFile(
        authManager: AuthManager,
        payload: String,
        token: String
    ): String? {
        val boundary = "drivestream-sync-${java.util.UUID.randomUUID()}"
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append("""{"name":"$CLOUD_FILE_NAME","parents":["appDataFolder"]}""")
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(payload)
            append("\r\n--$boundary--\r\n")
        }.toRequestBody("multipart/related; boundary=$boundary".toMediaType())

        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .post(multipartBody)
            .build()
        val (code, body, _) = executeAuthedDriveRequest(authManager, req, token)
        if (code !in 200..299) throw IOException("Drive appData create error ($code)")
        return runCatching { JSONObject(body).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private suspend fun executeAuthedDriveRequest(
        authManager: AuthManager,
        baseRequest: Request,
        initialToken: String
    ): Triple<Int, String, String> {
        var activeToken = initialToken
        for (attempt in 0 until 2) {
            val req = baseRequest.newBuilder()
                .header("Authorization", "Bearer $activeToken")
                .build()
            val (code, body) = cloudClient.newCall(req).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
            if (code == 401 && attempt == 0) {
                activeToken = authManager.refreshAccessToken()
                continue
            }
            if (code == 403 && (body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT"))) {
                throw InsufficientScopeException()
            }
            return Triple(code, body, activeToken)
        }
        throw IOException("Unauthorized")
    }

    private class InsufficientScopeException : Exception()

    companion object {
        const val CLOUD_FILE_NAME = "drivestream_sync.json"
        private const val KEY_PEER_HOST = "peer_sync_host"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
        }

        fun mergeStates(local: DriveStreamSyncState, remote: DriveStreamSyncState): DriveStreamSyncState {
            val mergedDeleted = linkedMapOf<String, Double>()
            for ((k, v) in local.deletedLinkIds) {
                val key = k.lowercase()
                mergedDeleted[key] = maxOf(mergedDeleted[key] ?: 0.0, v)
            }
            for ((k, v) in remote.deletedLinkIds) {
                val key = k.lowercase()
                mergedDeleted[key] = maxOf(mergedDeleted[key] ?: 0.0, v)
            }

            val linksById = linkedMapOf<String, DriveStreamSyncState.SyncedSavedLink>()
            for (item in local.savedLinks + remote.savedLinks) {
                val key = item.id.lowercase()
                val normalized = item.copy(id = key)
                val existing = linksById[key]
                if (existing == null ||
                    normalized.dateAdded > existing.dateAdded ||
                    (normalized.dateAdded == existing.dateAdded && normalized.title.length > existing.title.length)
                ) {
                    linksById[key] = normalized
                }
            }

            val linksByCanonicalUrl = linkedMapOf<String, DriveStreamSyncState.SyncedSavedLink>()
            for (item in linksById.values) {
                val deletedAt = mergedDeleted[item.id]
                if (deletedAt != null && deletedAt >= item.dateAdded) {
                    continue
                }
                val urlKey = canonicalUrlKey(item.urlString)
                val existing = linksByCanonicalUrl[urlKey]
                if (existing == null || item.dateAdded > existing.dateAdded) {
                    linksByCanonicalUrl[urlKey] = item
                }
            }

            val sortedLinks = linksByCanonicalUrl.values.sortedWith(
                compareByDescending<DriveStreamSyncState.SyncedSavedLink> { it.dateAdded }
                    .thenBy { it.id }
            )

            val mergedProgress = local.watchProgress.toMutableMap()
            for ((fileId, remoteEntry) in remote.watchProgress) {
                val localEntry = mergedProgress[fileId]
                if (localEntry == null ||
                    remoteEntry.updatedAt > localEntry.updatedAt ||
                    (remoteEntry.updatedAt == localEntry.updatedAt && remoteEntry.positionSeconds > localEntry.positionSeconds)
                ) {
                    mergedProgress[fileId] = remoteEntry
                }
            }

            val mergedCovers = local.customCovers.toMutableMap()
            for ((showKey, remoteCover) in remote.customCovers) {
                val localCover = mergedCovers[showKey]
                if (localCover == null) {
                    mergedCovers[showKey] = remoteCover
                } else {
                    val localTs = localCover.updatedAt ?: 0.0
                    val remoteTs = remoteCover.updatedAt ?: 0.0
                    if (remoteTs > localTs) {
                        mergedCovers[showKey] = remoteCover
                    } else if (abs(remoteTs - localTs) < 0.001) {
                        val remoteScore = (if (remoteCover.posterUrl != null) 1 else 0) + (if (remoteCover.tvmazeShowId != null) 1 else 0)
                        val localScore = (if (localCover.posterUrl != null) 1 else 0) + (if (localCover.tvmazeShowId != null) 1 else 0)
                        if (remoteScore > localScore) {
                            mergedCovers[showKey] = remoteCover
                        }
                    }
                }
            }

            return DriveStreamSyncState(
                version = 1,
                savedLinks = sortedLinks,
                deletedLinkIds = mergedDeleted,
                watchProgress = mergedProgress,
                customCovers = mergedCovers
            )
        }

        fun encodeSyncState(state: DriveStreamSyncState): String {
            val root = JSONObject()
            root.put("version", state.version)

            val linksArr = JSONArray()
            for (link in state.savedLinks) {
                linksArr.put(
                    JSONObject().apply {
                        put("id", link.id)
                        put("title", link.title)
                        put("urlString", link.urlString)
                        put("dateAdded", link.dateAdded)
                    }
                )
            }
            root.put("savedLinks", linksArr)

            val deletedObj = JSONObject()
            for ((id, ts) in state.deletedLinkIds) {
                deletedObj.put(id, ts)
            }
            root.put("deletedLinkIds", deletedObj)

            val progressObj = JSONObject()
            for ((fileId, entry) in state.watchProgress) {
                progressObj.put(
                    fileId,
                    JSONObject().apply {
                        put("positionSeconds", entry.positionSeconds)
                        put("durationSeconds", entry.durationSeconds)
                        put("updatedAt", entry.updatedAt)
                    }
                )
            }
            root.put("watchProgress", progressObj)

            val coversObj = JSONObject()
            for ((showKey, cover) in state.customCovers) {
                coversObj.put(
                    showKey,
                    JSONObject().apply {
                        if (cover.posterUrl != null) put("posterUrl", cover.posterUrl)
                        if (cover.tvmazeShowId != null) put("tvmazeShowId", cover.tvmazeShowId)
                        if (cover.updatedAt != null) put("updatedAt", cover.updatedAt)
                    }
                )
            }
            root.put("customCovers", coversObj)

            return root.toString()
        }

        fun decodeSyncState(rawJson: String): DriveStreamSyncState? {
            return runCatching {
                val root = JSONObject(rawJson)
                val version = root.optInt("version", 1)

                val savedLinks = buildList {
                    val arr = root.optJSONArray("savedLinks") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        val urlString = obj.optString("urlString")
                        if (id.isNotBlank() && urlString.isNotBlank()) {
                            add(
                                DriveStreamSyncState.SyncedSavedLink(
                                    id = id.lowercase(),
                                    title = obj.optString("title"),
                                    urlString = urlString,
                                    dateAdded = obj.optDouble("dateAdded", 0.0).takeIf { !it.isNaN() } ?: 0.0
                                )
                            )
                        }
                    }
                }

                val deletedLinkIds = linkedMapOf<String, Double>()
                root.optJSONObject("deletedLinkIds")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val ts = obj.optDouble(k, Double.NaN)
                        if (!ts.isNaN() && k.isNotBlank()) {
                            deletedLinkIds[k.lowercase()] = ts
                        }
                    }
                }

                val watchProgress = linkedMapOf<String, DriveStreamSyncState.WatchProgressEntry>()
                root.optJSONObject("watchProgress")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val fileId = keys.next()
                        val entryObj = obj.optJSONObject(fileId) ?: continue
                        val pos = entryObj.optDouble("positionSeconds", Double.NaN)
                        val dur = entryObj.optDouble("durationSeconds", 0.0)
                        val upd = entryObj.optDouble("updatedAt", Double.NaN)
                        if (!pos.isNaN() && !upd.isNaN() && fileId.isNotBlank()) {
                            watchProgress[fileId] = DriveStreamSyncState.WatchProgressEntry(
                                positionSeconds = pos,
                                durationSeconds = if (dur.isNaN()) 0.0 else dur,
                                updatedAt = upd
                            )
                        }
                    }
                }

                val customCovers = linkedMapOf<String, CustomCoverMatch>()
                root.optJSONObject("customCovers")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val showKey = keys.next()
                        val coverObj = obj.optJSONObject(showKey) ?: continue
                        val posterUrl = if (coverObj.has("posterUrl") && !coverObj.isNull("posterUrl")) {
                            coverObj.optString("posterUrl").takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                        val tvMazeId = if (coverObj.has("tvmazeShowId") && !coverObj.isNull("tvmazeShowId")) {
                            coverObj.optInt("tvmazeShowId")
                        } else {
                            null
                        }
                        val updatedAt = if (coverObj.has("updatedAt") && !coverObj.isNull("updatedAt")) {
                            coverObj.optDouble("updatedAt").takeIf { !it.isNaN() }
                        } else {
                            null
                        }
                        if (showKey.isNotBlank()) {
                            customCovers[showKey] = CustomCoverMatch(
                                posterUrl = posterUrl,
                                tvmazeShowId = tvMazeId,
                                updatedAt = updatedAt
                            )
                        }
                    }
                }

                DriveStreamSyncState(
                    version = version,
                    savedLinks = savedLinks,
                    deletedLinkIds = deletedLinkIds,
                    watchProgress = watchProgress,
                    customCovers = customCovers
                )
            }.getOrNull()
        }

        private fun cleanHost(raw: String): String {
            var text = raw.trim()
            if (text.lowercase().startsWith("http://")) text = text.drop(7)
            else if (text.lowercase().startsWith("https://")) text = text.drop(8)
            val slash = text.indexOf('/')
            if (slash >= 0) text = text.substring(0, slash)
            val colon = text.lastIndexOf(':')
            if (colon >= 0 && !text.contains(']') && text.substring(colon + 1).toIntOrNull() != null) {
                text = text.substring(0, colon)
            }
            return text.trim()
        }

        private fun canonicalUrlKey(raw: String): String {
            val trimmed = raw.trim()
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return trimmed.lowercase()
            val host = uri.host?.lowercase() ?: return trimmed.lowercase()
            val path = uri.encodedPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}$path$query"
        }
    }
}
