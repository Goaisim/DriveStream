package com.drake.drivestream

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Immutable
data class SoulseekFileResult(
    val id: String,
    val username: String,
    val filename: String,
    val basename: String,
    val size: Long,
    val qualityTag: String,
    val uploadSpeed: Double,
    val hasFreeUploadSlot: Boolean,
    val queueLength: Int
)

@Immutable
data class SoulseekFolderPack(
    val id: String,
    val username: String,
    val parentFolder: String,
    val folderDisplayName: String,
    val uploadSpeed: Double,
    val hasFreeUploadSlot: Boolean,
    val queueLength: Int,
    val totalSize: Long,
    val fileCount: Int,
    val qualityTag: String,
    val summary: String,
    val files: List<SoulseekFileResult>
)

@Immutable
data class SoulseekTransferItem(
    val id: String,
    val username: String,
    val filename: String,
    val title: String,
    val size: Long,
    val percent: Double,
    val speedBytesPerSec: Double,
    val stage: String,
    val statusText: String,
    val driveFileId: String,
    val driveFolderPath: String,
    val error: String
)

object SoulseekFormatting {
    fun formatSize(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = (bytes / 1_048_576.0).coerceAtLeast(1.0)
        return String.format(Locale.US, "%.0f MB", mb)
    }

    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return "0 KB/s"
        val mbs = bytesPerSec / 1_048_576.0
        if (mbs >= 0.1) {
            return String.format(Locale.US, "%.1f MB/s", mbs)
        }
        val kbs = (bytesPerSec / 1024.0).coerceAtLeast(1.0)
        return String.format(Locale.US, "%.0f KB/s", kbs)
    }
}

class SoulseekService private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncManager by lazy { SyncManager.getInstance(appContext) }
    private val authManager by lazy { AuthManager.getInstance(appContext) }

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _bridgeReachable = MutableStateFlow(false)
    val bridgeReachable: StateFlow<Boolean> = _bridgeReachable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _folders = MutableStateFlow<List<SoulseekFolderPack>>(emptyList())
    val folders: StateFlow<List<SoulseekFolderPack>> = _folders.asStateFlow()

    private val _files = MutableStateFlow<List<SoulseekFileResult>>(emptyList())
    val files: StateFlow<List<SoulseekFileResult>> = _files.asStateFlow()

    private val _transfers = MutableStateFlow<List<SoulseekTransferItem>>(emptyList())
    val transfers: StateFlow<List<SoulseekTransferItem>> = _transfers.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount.asStateFlow()

    private val _activeEnqueueId = MutableStateFlow<String?>(null)
    val activeEnqueueId: StateFlow<String?> = _activeEnqueueId.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    var onUploadCompleted: (() -> Unit)? = null

    private var searchJob: Job? = null
    private var pollingJob: Job? = null
    private var searchGeneration = 0
    private var hasSeededTransfers = false
    private val knownCompletedIds = mutableSetOf<String>()
    private var lastCompletedCount = 0

    fun setManualBridgeHost(rawHost: String) {
        val cleaned = SyncManager.cleanHost(rawHost)
        syncManager.setSoulseekBridgeHost(cleaned)
        if (cleaned.isEmpty()) {
            _bridgeReachable.value = false
            _statusMessage.value = "Not configured"
            return
        }
        scope.launch {
            val ok = checkBridgeHealth()
            if (ok) refreshTransfers()
        }
    }

    fun ensureConnectedAndRefresh() {
        scope.launch {
            val host = syncManager.soulseekBridgeHost.value
            if (host.isEmpty()) {
                scanWiFiForBridgeInternal()
            } else {
                checkBridgeHealth()
            }
            if (_bridgeReachable.value) {
                refreshTransfers()
            }
        }
    }

    suspend fun checkBridgeHealth(): Boolean {
        val host = syncManager.soulseekBridgeHost.value
        if (host.isEmpty()) {
            _bridgeReachable.value = false
            _statusMessage.value = "Not configured"
            return false
        }
        val ok = withContext(Dispatchers.IO) { probeBridgeHealth(host) }
        _bridgeReachable.value = ok
        _statusMessage.value = if (ok) {
            "Connected to $host:$BRIDGE_PORT"
        } else {
            "Bridge offline at $host:$BRIDGE_PORT"
        }
        return ok
    }

    fun scanWiFiForBridge() {
        if (_isScanning.value) return
        scope.launch {
            scanWiFiForBridgeInternal()
        }
    }

    private suspend fun scanWiFiForBridgeInternal() {
        if (_isScanning.value) return
        _isScanning.value = true
        _statusMessage.value = "Scanning local Wi-Fi (:$BRIDGE_PORT)…"
        try {
            val localIp = withContext(Dispatchers.IO) { AuthManager.detectLocalIpAddress() }
            val parts = localIp.split(".")
            if (parts.size != 4 || localIp == "127.0.0.1") {
                _bridgeReachable.value = false
                _statusMessage.value = "Could not detect Wi-Fi IP"
                return
            }
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val configured = syncManager.soulseekBridgeHost.value
            val candidates = buildList {
                if (configured.isNotEmpty()) add(configured)
                for (octet in 1..254) {
                    val ip = "$prefix.$octet"
                    if (ip != localIp && ip != configured) add(ip)
                }
            }

            val foundHost = withContext(Dispatchers.IO) {
                discoverBridgeHost(candidates)
            }
            if (foundHost != null) {
                syncManager.setSoulseekBridgeHost(foundHost)
                _bridgeReachable.value = true
                _statusMessage.value = "Connected to $foundHost:$BRIDGE_PORT"
                refreshTransfers()
            } else {
                _bridgeReachable.value = false
                _statusMessage.value = "No Soulseek bridge found on Wi-Fi"
            }
        } finally {
            _isScanning.value = false
        }
    }

    private suspend fun discoverBridgeHost(candidates: List<String>): String? = coroutineScope {
        val semaphore = Semaphore(48)
        val firstHit = AtomicReference<String?>(null)
        val jobs = candidates.map { host ->
            async {
                if (firstHit.get() != null) return@async null
                semaphore.withPermit {
                    if (firstHit.get() != null) return@withPermit null
                    if (probeBridgeHealth(host)) {
                        firstHit.compareAndSet(null, host)
                        host
                    } else {
                        null
                    }
                }
            }
        }
        for (deferred in jobs) {
            val res = runCatching { deferred.await() }.getOrNull()
            if (res != null) {
                jobs.forEach { it.cancel() }
                return@coroutineScope res
            }
        }
        null
    }

    private fun probeBridgeHealth(host: String): Boolean = runCatching {
        val req = Request.Builder()
            .url("http://$host:$BRIDGE_PORT/api/health")
            .get()
            .build()
        syncManager.scanClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching false
            val body = resp.body?.string().orEmpty()
            val obj = JSONObject(body)
            obj.optBoolean("ok", false) && obj.optString("service") == "drivestream-soulseek-bridge"
        }
    }.getOrDefault(false)

    fun startSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        searchJob?.cancel()
        searchGeneration += 1
        val generation = searchGeneration

        _isSearching.value = true
        _searchError.value = null
        _actionMessage.value = null
        _folders.value = emptyList()
        _files.value = emptyList()

        searchJob = scope.launch {
            try {
                runSearch(trimmed, generation)
            } finally {
                if (searchGeneration == generation) {
                    _isSearching.value = false
                }
            }
        }
    }

    private suspend fun runSearch(query: String, generation: Int) {
        val baseUrl = currentBridgeBaseUrl()
        if (baseUrl == null) {
            _searchError.value = "Set your PC Bridge IP or select Scan Wi-Fi first."
            return
        }

        val searchIdResult = withContext(Dispatchers.IO) {
            runCatching {
                val bodyJson = JSONObject().put("query", query).toString()
                val req = Request.Builder()
                    .url("$baseUrl/api/search")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                apiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    ensureSuccess(resp.code, raw)
                    JSONObject(raw).getString("searchId")
                }
            }
        }

        val searchId = searchIdResult.getOrElse { err ->
            if (searchGeneration == generation) {
                _searchError.value = err.message ?: "Search failed"
            }
            return
        }
        _bridgeReachable.value = true

        val encodedId = Uri.encode(searchId)
        for (attempt in 0 until 18) {
            if (searchGeneration != generation) return
            val pollResult = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url("$baseUrl/api/search/$encodedId")
                        .get()
                        .build()
                    apiClient.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        ensureSuccess(resp.code, raw)
                        parseSearchResults(raw)
                    }
                }
            }

            val parsed = pollResult.getOrElse { err ->
                if (searchGeneration == generation) {
                    _searchError.value = err.message ?: "Search poll failed"
                }
                return
            }

            if (searchGeneration != generation) return
            _folders.value = parsed.first
            _files.value = parsed.second
            if (parsed.third || attempt == 17) return
            delay(900L)
        }
    }

    fun downloadFolder(pack: SoulseekFolderPack, targetFolderId: String, onQueued: () -> Unit = {}) {
        enqueue(
            enqueueId = pack.id,
            username = pack.username,
            files = pack.files,
            label = "${pack.folderDisplayName} (${pack.fileCount} files)",
            targetFolderId = targetFolderId,
            onQueued = onQueued
        )
    }

    fun downloadFile(file: SoulseekFileResult, targetFolderId: String, onQueued: () -> Unit = {}) {
        enqueue(
            enqueueId = file.id,
            username = file.username,
            files = listOf(file),
            label = file.basename,
            targetFolderId = targetFolderId,
            onQueued = onQueued
        )
    }

    private fun enqueue(
        enqueueId: String,
        username: String,
        files: List<SoulseekFileResult>,
        label: String,
        targetFolderId: String,
        onQueued: () -> Unit
    ) {
        if (_activeEnqueueId.value != null) return
        val baseUrl = currentBridgeBaseUrl()
        if (baseUrl == null) {
            _actionMessage.value = "Bridge not configured."
            return
        }

        _activeEnqueueId.value = enqueueId
        _actionMessage.value = null

        scope.launch {
            try {
                val queuedCount = withContext(Dispatchers.IO) {
                    val accessToken = authManager.validAccessToken()
                    val refreshToken = authManager.currentRefreshToken().orEmpty()
                    val resolvedFolder = targetFolderId.trim().ifEmpty { DriveService.DEFAULT_FOLDER_ID }

                    val filesArr = JSONArray()
                    for (item in files) {
                        filesArr.put(
                            JSONObject()
                                .put("filename", item.filename)
                                .put("size", item.size)
                        )
                    }

                    val payload = JSONObject()
                        .put("username", username)
                        .put("files", filesArr)
                        .put("accessToken", accessToken)
                        .put("refreshToken", refreshToken)
                        .put("targetFolderId", resolvedFolder)
                        .toString()

                    val req = Request.Builder()
                        .url("$baseUrl/api/download")
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    apiClient.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        ensureSuccess(resp.code, raw)
                        JSONObject(raw).optInt("queued", files.size)
                    }
                }
                _actionMessage.value = "Queued $queuedCount file(s) from $label"
                refreshTransfers()
                onQueued()
            } catch (err: Exception) {
                _actionMessage.value = "Queue failed: ${err.message ?: "Unknown error"}"
            } finally {
                _activeEnqueueId.value = null
            }
        }
    }

    fun startPollingTransfers() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                if (syncManager.soulseekBridgeHost.value.isNotEmpty()) {
                    refreshTransfers()
                }
                delay(2000L)
            }
        }
    }

    fun stopPollingTransfers() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun refreshTransfers() {
        val baseUrl = currentBridgeBaseUrl() ?: return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/transfers")
                    .get()
                    .build()
                apiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    ensureSuccess(resp.code, raw)
                    parseTransfers(raw)
                }
            }
        }

        val parsed = result.getOrElse {
            _bridgeReachable.value = false
            return
        }

        _bridgeReachable.value = true
        _transfers.value = parsed.first
        _completedCount.value = parsed.second

        val currentCompletedIds = parsed.first.asSequence()
            .filter { it.stage == "completed" }
            .map { it.id }
            .toSet()

        val newlyCompleted = hasSeededTransfers && (
            currentCompletedIds.any { it !in knownCompletedIds } ||
                parsed.second > lastCompletedCount
            )

        knownCompletedIds.addAll(currentCompletedIds)
        lastCompletedCount = parsed.second
        hasSeededTransfers = true

        if (newlyCompleted) {
            onUploadCompleted?.invoke()
        }
    }

    private fun currentBridgeBaseUrl(): String? {
        val host = syncManager.soulseekBridgeHost.value
        if (host.isEmpty()) return null
        return "http://$host:$BRIDGE_PORT"
    }

    private fun ensureSuccess(code: Int, rawBody: String) {
        if (code in 200..299) return
        val errMsg = runCatching { JSONObject(rawBody).optString("error") }.getOrNull()
        throw IllegalStateException(
            if (!errMsg.isNullOrBlank()) errMsg else "Bridge returned HTTP $code"
        )
    }

    private fun parseSearchResults(rawJson: String): Triple<List<SoulseekFolderPack>, List<SoulseekFileResult>, Boolean> {
        val root = JSONObject(rawJson)
        val isComplete = root.optBoolean("isComplete", false)

        val filesList = buildList {
            val arr = root.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(parseFileResult(it)) }
            }
        }

        val foldersList = buildList {
            val arr = root.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val innerFiles = buildList {
                    val fArr = obj.optJSONArray("files") ?: JSONArray()
                    for (j in 0 until fArr.length()) {
                        fArr.optJSONObject(j)?.let { add(parseFileResult(it)) }
                    }
                }
                add(
                    SoulseekFolderPack(
                        id = obj.optString("id"),
                        username = obj.optString("username"),
                        parentFolder = obj.optString("parentFolder"),
                        folderDisplayName = obj.optString("folderDisplayName"),
                        uploadSpeed = obj.optDouble("uploadSpeed", 0.0),
                        hasFreeUploadSlot = obj.optBoolean("hasFreeUploadSlot", false),
                        queueLength = obj.optInt("queueLength", 0),
                        totalSize = obj.optLong("totalSize", 0L),
                        fileCount = obj.optInt("fileCount", innerFiles.size),
                        qualityTag = obj.optString("qualityTag", "Video"),
                        summary = obj.optString("summary"),
                        files = innerFiles
                    )
                )
            }
        }

        return Triple(foldersList, filesList, isComplete)
    }

    private fun parseFileResult(obj: JSONObject): SoulseekFileResult {
        return SoulseekFileResult(
            id = obj.optString("id"),
            username = obj.optString("username"),
            filename = obj.optString("filename"),
            basename = obj.optString("basename"),
            size = obj.optLong("size", 0L),
            qualityTag = obj.optString("qualityTag", "Video"),
            uploadSpeed = obj.optDouble("uploadSpeed", 0.0),
            hasFreeUploadSlot = obj.optBoolean("hasFreeUploadSlot", false),
            queueLength = obj.optInt("queueLength", 0)
        )
    }

    private fun parseTransfers(rawJson: String): Pair<List<SoulseekTransferItem>, Int> {
        val root = JSONObject(rawJson)
        val completed = root.optInt("completedCount", 0)
        val list = buildList {
            val arr = root.optJSONArray("transfers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    SoulseekTransferItem(
                        id = obj.optString("id"),
                        username = obj.optString("username"),
                        filename = obj.optString("filename"),
                        title = obj.optString("title"),
                        size = obj.optLong("size", 0L),
                        percent = obj.optDouble("percent", 0.0),
                        speedBytesPerSec = obj.optDouble("speedBytesPerSec", 0.0),
                        stage = obj.optString("stage", "queued"),
                        statusText = obj.optString("statusText"),
                        driveFileId = obj.optString("driveFileId"),
                        driveFolderPath = obj.optString("driveFolderPath"),
                        error = obj.optString("error")
                    )
                )
            }
        }
        return list to completed
    }

    companion object {
        const val BRIDGE_PORT = 5031
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: SoulseekService? = null

        fun getInstance(context: Context): SoulseekService {
            return instance ?: synchronized(this) {
                instance ?: SoulseekService(context.applicationContext).also { instance = it }
            }
        }
    }
}
