package com.drake.drivestream

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class AuthSession(
    val authUrl: String,
    val codeVerifier: String
)

class AuthManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("drivestream_auth", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val refreshMutex = Mutex()
    private var activeRefresh: CompletableDeferred<String>? = null

    private val _isAuthenticated = MutableStateFlow(hasStoredAuth())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _hasAppDataScope = MutableStateFlow(storedHasAppDataScope())
    val hasAppDataScope: StateFlow<Boolean> = _hasAppDataScope.asStateFlow()

    private val _showInAppAuthWebView = MutableStateFlow(false)
    val showInAppAuthWebView: StateFlow<Boolean> = _showInAppAuthWebView.asStateFlow()

    private var companionServer: CompanionWebServer? = null

    private val _companionUrl = MutableStateFlow("http://${detectLocalIpAddress()}:$COMPANION_PORT")
    val companionUrl: StateFlow<String> = _companionUrl.asStateFlow()

    private fun hasStoredAuth(): Boolean {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    private fun storedHasAppDataScope(): Boolean {
        val scopes = prefs.getString(KEY_GRANTED_SCOPES, "").orEmpty()
        return scopes.split(Regex("\\s+")).any { it == APPDATA_SCOPE || it.endsWith("/drive.appdata") }
    }

    fun markAppDataScopeRevoked() {
        prefs.edit().remove(KEY_GRANTED_SCOPES).apply()
        _hasAppDataScope.value = false
    }

    fun startCompanionServer() {
        refreshLocalIp()
        if (companionServer == null) {
            companionServer = CompanionWebServer(
                authManager = this,
                savedLinksManager = SavedLinksManager.getInstance(appContext),
                driveSettings = DriveSettings.getInstance(appContext),
                syncManager = SyncManager.getInstance(appContext)
            ).also { it.start() }
        }
    }

    fun refreshLocalIp() {
        _companionUrl.value = "http://${detectLocalIpAddress()}:$COMPANION_PORT"
    }

    fun openInAppSignIn() {
        _authError.value = null
        _isSigningIn.value = false
        _showInAppAuthWebView.value = true
    }

    fun dismissInAppSignIn() {
        _showInAppAuthWebView.value = false
    }

    fun reportAuthError(message: String?) {
        _authError.value = message
    }

    fun createAuthSession(saveAsPending: Boolean = true): AuthSession {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        if (saveAsPending) {
            prefs.edit().putString(KEY_PENDING_VERIFIER, verifier).apply()
        }
        _authError.value = null

        val url = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
            .toString()
        return AuthSession(authUrl = url, codeVerifier = verifier)
    }

    fun startNewAuthSession(): String = createAuthSession(saveAsPending = true).authUrl

    fun launchTvBrowserSignIn(context: Context) {
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val externalBrowsers = runCatching {
            context.packageManager.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { info ->
                    val pkg = info.activityInfo?.packageName.orEmpty()
                    pkg.isNotBlank() &&
                        pkg != context.packageName &&
                        pkg != "android" &&
                        !pkg.contains("stubs", ignoreCase = true)
                }
        }.getOrDefault(emptyList())

        if (externalBrowsers.isEmpty()) {
            openInAppSignIn()
            return
        }

        val authUrl = startNewAuthSession()
        _isSigningIn.value = true
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            _isSigningIn.value = false
            openInAppSignIn()
        } catch (_: Exception) {
            _isSigningIn.value = false
            openInAppSignIn()
        }
    }

    suspend fun handleRedirectUri(uri: Uri): Result<Unit> {
        val err = uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            val msg = "Google Sign-In error: $err"
            _authError.value = msg
            _isSigningIn.value = false
            return Result.failure(IllegalStateException(msg))
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            val msg = "Authorization code missing from redirect URL."
            _authError.value = msg
            _isSigningIn.value = false
            return Result.failure(IllegalArgumentException(msg))
        }
        return exchangeCode(code)
    }

    suspend fun completeAuthFromInput(rawInput: String): Result<Unit> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            val msg = "Please paste a redirect URL, authorization code, or refresh token (1//...) first."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        if (trimmed.startsWith("{")) {
            val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
            if (parsed != null) {
                val refresh = parsed.optString("refresh_token", "").trim()
                val access = parsed.optString("access_token", "").trim()
                val exp = parsed.optLong("expiration_ms", 0L)
                val scopes = parsed.optString("granted_scopes", parsed.optString("scope", "")).trim()
                if (refresh.isNotBlank()) {
                    return importTokensFromPeer(refresh, access, exp, scopes)
                }
                val jsonCode = parsed.optString("code", "").trim()
                if (jsonCode.isNotBlank()) {
                    return exchangeCode(jsonCode)
                }
            }
        }

        val xmlRefresh = Regex("""oauth_refresh_token"[^>]*>([^<]+)<""").find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!xmlRefresh.isNullOrBlank()) {
            return signInWithRefreshToken(xmlRefresh)
        }

        if (trimmed.startsWith("1//")) {
            return signInWithRefreshToken(trimmed)
        }

        if (trimmed.contains("://") || trimmed.contains("code=") || trimmed.contains("error=") || trimmed.contains("refresh_token=")) {
            val parsable = if (trimmed.contains("://")) trimmed else "https://dummy.local/?${trimmed.trimStart('?')}"
            val uri = Uri.parse(parsable)
            val err = uri.getQueryParameter("error")
            if (!err.isNullOrBlank()) {
                val msg = "Google Sign-In error: $err"
                _authError.value = msg
                return Result.failure(IllegalStateException(msg))
            }
            val pastedRefresh = uri.getQueryParameter("refresh_token")?.trim()
            if (!pastedRefresh.isNullOrBlank()) {
                return signInWithRefreshToken(pastedRefresh)
            }
            val code = uri.getQueryParameter("code")?.trim()
            if (code.isNullOrBlank()) {
                val msg = "No valid code or refresh_token found in the pasted URL."
                _authError.value = msg
                return Result.failure(IllegalArgumentException(msg))
            }
            return exchangeCode(code)
        }

        return exchangeCode(trimmed)
    }

    suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            val msg = "Authorization code is empty."
            _authError.value = msg
            return@withContext Result.failure(IllegalArgumentException(msg))
        }
        val cleanVerifier = codeVerifier.trim()
        if (cleanVerifier.isEmpty()) {
            val msg = "No active PKCE session. Click 'Open Google Sign-In' first."
            _authError.value = msg
            return@withContext Result.failure(IllegalStateException(msg))
        }

        _isSigningIn.value = true
        _authError.value = null
        try {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("code", cleanCode)
                .add("code_verifier", cleanVerifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val tokenJson = executeTokenRequest(form)
            saveTokens(tokenJson)
            prefs.edit().remove(KEY_PENDING_VERIFIER).apply()
            _isAuthenticated.value = true
            _showInAppAuthWebView.value = false
            _authError.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = e.message ?: "Token exchange failed"
            _authError.value = msg
            Result.failure(e)
        } finally {
            _isSigningIn.value = false
        }
    }

    suspend fun signInWithRefreshToken(refreshToken: String, fallbackScopes: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cleanRefresh = refreshToken.trim()
            if (cleanRefresh.isEmpty()) {
                val msg = "Refresh token is empty."
                _authError.value = msg
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            _isSigningIn.value = true
            _authError.value = null
            try {
                val form = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", cleanRefresh)
                    .add("grant_type", "refresh_token")
                    .build()

                val tokenJson = executeTokenRequest(form)
                saveTokens(
                    json = tokenJson,
                    fallbackRefreshToken = cleanRefresh,
                    fallbackScopes = fallbackScopes
                )
                prefs.edit().remove(KEY_PENDING_VERIFIER).apply()
                _isAuthenticated.value = true
                _showInAppAuthWebView.value = false
                _authError.value = null
                Result.success(Unit)
            } catch (e: Exception) {
                val msg = e.message ?: "Refresh token sign-in failed"
                _authError.value = msg
                Result.failure(e)
            } finally {
                _isSigningIn.value = false
            }
        }

    suspend fun importTokensFromPeer(
        refreshToken: String,
        accessToken: String = "",
        expirationMs: Long = 0L,
        grantedScopes: String = ""
    ): Result<Unit> {
        val cleanRefresh = refreshToken.trim()
        val cleanAccess = accessToken.trim()
        val cleanScopes = grantedScopes.trim()
        if (cleanRefresh.isEmpty() && cleanAccess.isEmpty()) {
            val msg = "Missing refresh_token in payload."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        if (cleanAccess.isNotEmpty() &&
            expirationMs - System.currentTimeMillis() > 60_000L &&
            cleanScopes.isNotEmpty()
        ) {
            persistTokenValues(cleanAccess, cleanRefresh, expirationMs, cleanScopes)
            prefs.edit().remove(KEY_PENDING_VERIFIER).apply()
            _isAuthenticated.value = true
            _showInAppAuthWebView.value = false
            _authError.value = null
            return Result.success(Unit)
        }

        if (cleanRefresh.isEmpty()) {
            val msg = "Token payload has expired access_token and no refresh_token."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        return signInWithRefreshToken(cleanRefresh, fallbackScopes = cleanScopes.ifBlank { null })
    }

    fun exportTokensJson(folderId: String): String {
        val obj = JSONObject().apply {
            put("authenticated", _isAuthenticated.value)
            put("client_id", CLIENT_ID)
            put("device_model", Build.MODEL.orEmpty())
            put("refresh_token", prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty())
            put("access_token", prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty())
            put("expiration_ms", prefs.getLong(KEY_EXPIRATION_MS, 0L))
            put("granted_scopes", prefs.getString(KEY_GRANTED_SCOPES, "").orEmpty())
            put("folder_id", folderId)
        }
        return obj.toString()
    }

    suspend fun cloneTokensFromPeer(peerInput: String): Result<String> = withContext(Dispatchers.IO) {
        val hostPort = normalizePeerHostPort(peerInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Enter the IP of a signed-in DriveStream TV (e.g. 192.168.0.190).")
            )
        try {
            val req = Request.Builder()
                .url("http://$hostPort/api/tokens")
                .get()
                .build()
            val jsonText = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Peer $hostPort returned HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
            val obj = JSONObject(jsonText)
            if (!obj.optBoolean("authenticated", false)) {
                throw IllegalStateException("Peer TV at $hostPort is not signed in to Google Drive.")
            }
            val res = importTokensFromPeer(
                refreshToken = obj.optString("refresh_token", ""),
                accessToken = obj.optString("access_token", ""),
                expirationMs = obj.optLong("expiration_ms", 0L),
                grantedScopes = obj.optString("granted_scopes", "")
            )
            if (res.isFailure) {
                throw res.exceptionOrNull() ?: IllegalStateException("Token import failed")
            }
            Result.success(hostPort)
        } catch (e: Exception) {
            val msg = "Could not clone from $hostPort: ${e.message ?: "unreachable"}"
            _authError.value = msg
            Result.failure(IllegalStateException(msg, e))
        }
    }

    private fun normalizePeerHostPort(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.contains(':')) trimmed else "$trimmed:$COMPANION_PORT"
    }

    private suspend fun exchangeCode(code: String): Result<Unit> {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            val msg = "Authorization code is empty."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null).orEmpty()
        if (verifier.isBlank()) {
            val msg = "No active PKCE session. Click 'Open Google Sign-In' first."
            _authError.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        return exchangeCodeForTokens(cleanCode, verifier)
    }

    fun getValidAccessToken(): String = runBlocking(Dispatchers.IO) {
        validAccessToken()
    }

    suspend fun validAccessToken(): String {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiryMs = prefs.getLong(KEY_EXPIRATION_MS, 0L)
        if (!token.isNullOrBlank() && expiryMs - System.currentTimeMillis() > 60_000L) {
            return token
        }
        return refreshAccessToken()
    }

    suspend fun refreshAccessToken(): String {
        val (deferred, isOwner) = refreshMutex.withLock {
            val existing = activeRefresh
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<String>()
                activeRefresh = created
                created to true
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        return try {
            val refreshed = withContext(Dispatchers.IO) {
                val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
                if (refreshToken.isNullOrBlank()) {
                    signOut()
                    throw IllegalStateException("Session expired. Please sign in with Google again.")
                }

                val form = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .build()

                val tokenJson = executeTokenRequest(form)
                saveTokens(tokenJson)
                tokenJson.getString("access_token")
            }
            deferred.complete(refreshed)
            refreshed
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            refreshMutex.withLock {
                if (activeRefresh === deferred) {
                    activeRefresh = null
                }
            }
        }
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRATION_MS)
            .remove(KEY_GRANTED_SCOPES)
            .apply()
        _isAuthenticated.value = false
        _hasAppDataScope.value = false
        _authError.value = null
        SyncManager.getInstance(appContext).resetCloudStateOnSignOut()
    }

    private fun executeTokenRequest(body: FormBody): JSONObject {
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if ((response.code == 400 || response.code == 401) && rawBody.contains("invalid_grant")) {
                    signOut()
                }
                throw IOException("Token error (${response.code}): $rawBody")
            }
            return JSONObject(rawBody)
        }
    }

    private fun saveTokens(
        json: JSONObject,
        fallbackRefreshToken: String? = null,
        fallbackScopes: String? = null
    ) {
        val accessToken = json.getString("access_token")
        val expiresInSec = json.optLong("expires_in", 3600L)
        val refreshToken = json.optString("refresh_token", "").ifBlank { fallbackRefreshToken.orEmpty() }
        val scopeStr = json.optString("scope", "").ifBlank { fallbackScopes.orEmpty() }
        persistTokenValues(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expirationMs = System.currentTimeMillis() + expiresInSec * 1000L,
            scopeStr = scopeStr
        )
    }

    private fun persistTokenValues(
        accessToken: String,
        refreshToken: String,
        expirationMs: Long,
        scopeStr: String
    ) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken.isNotBlank()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            if (scopeStr.isNotBlank()) {
                putString(KEY_GRANTED_SCOPES, scopeStr)
            }
            putLong(KEY_EXPIRATION_MS, expirationMs)
            apply()
        }
        if (scopeStr.isNotBlank()) {
            _hasAppDataScope.value = storedHasAppDataScope()
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val CLIENT_ID = "134960325804-ohug3donf2m9l58oj794tah7f0il4arb.apps.googleusercontent.com"
        const val REDIRECT_SCHEME = "com.googleusercontent.apps.134960325804-ohug3donf2m9l58oj794tah7f0il4arb"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauthredirect"
        const val APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.appdata"
        const val COMPANION_PORT = 8766

        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_EXPIRATION_MS = "oauth_expiration_ms"
        private const val KEY_PENDING_VERIFIER = "pending_pkce_verifier"
        private const val KEY_GRANTED_SCOPES = "oauth_granted_scopes"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }

        fun detectLocalIpAddress(): String {
            try {
                val enumIfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                var fallbackIp: String? = null
                for (iface in java.util.Collections.list(enumIfaces)) {
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    for (addr in java.util.Collections.list(iface.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (addr.isSiteLocalAddress) return host
                            if (fallbackIp == null) fallbackIp = host
                        }
                    }
                }
                return fallbackIp ?: "127.0.0.1"
            } catch (_: Exception) {
                return "127.0.0.1"
            }
        }
    }
}

// MARK: - Companion Web Server on 0.0.0.0:8766

private class CompanionWebServer(
    private val authManager: AuthManager,
    private val savedLinksManager: SavedLinksManager,
    private val driveSettings: DriveSettings,
    private val syncManager: SyncManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", AuthManager.COMPANION_PORT))
                }
                serverSocket = socket
                while (!socket.isClosed) {
                    val client = socket.accept()
                    launch { handleClient(client) }
                }
            } catch (_: Exception) {
                serverSocket = null
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val rawHeader = readHeaderBytes(input) ?: return
            val headerText = String(rawHeader, StandardCharsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) return

            val method = requestLine[0].uppercase()
            val fullTarget = requestLine[1]
            val qIdx = fullTarget.indexOf('?')
            val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
            val query = if (qIdx >= 0) fullTarget.substring(qIdx + 1) else ""

            var contentLength = 0
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull()?.coerceIn(0, 2_097_152) ?: 0
                    }
                }
            }

            val body = if (contentLength > 0) readFixedBytes(input, contentLength) else ""

            when {
                method == "GET" && path == "/api/sync" -> {
                    sendJson(s, 200, syncManager.exportLocalStateJson())
                }

                method == "POST" && path == "/api/sync" -> {
                    val result = runCatching {
                        runBlocking { syncManager.receivePeerSyncJson(body) }
                    }
                    if (result.isSuccess) {
                        sendJson(s, 200, result.getOrThrow())
                    } else {
                        val errJson = JSONObject()
                            .put("error", result.exceptionOrNull()?.message ?: "Invalid sync payload")
                            .toString()
                        sendJson(s, 400, errJson)
                    }
                }

                method == "GET" && path == "/api/tokens" -> {
                    sendJson(s, 200, authManager.exportTokensJson(driveSettings.folderId.value))
                }

                method == "POST" && path == "/api/tokens" -> {
                    val result = runCatching {
                        if (body.isBlank()) throw IllegalArgumentException("Empty JSON body")
                        val obj = JSONObject(body)
                        val res = runBlocking {
                            authManager.importTokensFromPeer(
                                refreshToken = obj.optString("refresh_token", ""),
                                accessToken = obj.optString("access_token", ""),
                                expirationMs = obj.optLong("expiration_ms", 0L),
                                grantedScopes = obj.optString("granted_scopes", obj.optString("scope", ""))
                            )
                        }
                        if (res.isFailure) {
                            throw res.exceptionOrNull() ?: IllegalStateException("Token import failed")
                        }
                        syncManager.triggerManualSync()
                        JSONObject()
                            .put("status", "ok")
                            .put("authenticated", authManager.isAuthenticated.value)
                            .toString()
                    }
                    if (result.isSuccess) {
                        sendJson(s, 200, result.getOrThrow())
                    } else {
                        val errJson = JSONObject()
                            .put("error", result.exceptionOrNull()?.message ?: "Invalid token payload")
                            .toString()
                        sendJson(s, 400, errJson)
                    }
                }

                method == "GET" && path == "/oauth/start" -> {
                    val authUrl = authManager.startNewAuthSession()
                    sendRedirect(s, 302, authUrl)
                }

                method == "POST" && path == "/oauth/complete" -> {
                    val params = parseForm(body)
                    val codeInput = params["code"]?.trim().orEmpty()
                    if (codeInput.isEmpty()) {
                        sendRedirect(
                            s,
                            303,
                            "/?err=${urlEnc("Paste a redirect URL, authorization code, or refresh token (1//...) before submitting.")}"
                        )
                        return
                    }
                    val result = runBlocking { authManager.completeAuthFromInput(codeInput) }
                    if (result.isSuccess) {
                        syncManager.triggerManualSync()
                        sendRedirect(s, 303, "/?msg=${urlEnc("Signed in to Google Drive on TV!")}")
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Sign-in failed"
                        sendRedirect(s, 303, "/?err=${urlEnc(err)}")
                    }
                }

                method == "POST" && path == "/oauth/clone" -> {
                    val params = parseForm(body)
                    val peer = params["peer"]?.trim().orEmpty()
                    if (peer.isEmpty()) {
                        sendRedirect(
                            s,
                            303,
                            "/?err=${urlEnc("Enter the IP of a signed-in DriveStream TV first (e.g. 192.168.0.190).")}"
                        )
                        return
                    }
                    val result = runBlocking { authManager.cloneTokensFromPeer(peer) }
                    if (result.isSuccess) {
                        syncManager.triggerManualSync()
                        sendRedirect(s, 303, "/?msg=${urlEnc("Cloned Google Drive session from ${result.getOrThrow()}!")}")
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Peer token clone failed"
                        sendRedirect(s, 303, "/?err=${urlEnc(err)}")
                    }
                }

                method == "POST" && path == "/oauth/signout" -> {
                    authManager.signOut()
                    syncManager.resetCloudStateOnSignOut()
                    sendRedirect(s, 303, "/?msg=${urlEnc("Signed out.")}")
                }

                method == "POST" && path == "/links/add" -> {
                    val params = parseForm(body)
                    val added = savedLinksManager.addLink(
                        title = params["title"].orEmpty(),
                        urlString = params["url"].orEmpty()
                    )
                    if (added != null) {
                        syncManager.scheduleCloudSyncAfterServerMutation()
                        sendRedirect(s, 303, "/?msg=${urlEnc("Added ${added.title} to Fire TV.")}")
                    } else {
                        sendRedirect(s, 303, "/?err=${urlEnc("Invalid URL.")}")
                    }
                }

                method == "POST" && path == "/links/edit" -> {
                    val params = parseForm(body)
                    val updated = savedLinksManager.updateLink(
                        id = params["id"].orEmpty(),
                        title = params["title"].orEmpty(),
                        urlString = params["url"].orEmpty()
                    )
                    if (updated != null) {
                        syncManager.scheduleCloudSyncAfterServerMutation()
                        sendRedirect(s, 303, "/?msg=${urlEnc("Updated ${updated.title}.")}")
                    } else {
                        sendRedirect(s, 303, "/?err=${urlEnc("Invalid URL or link.")}")
                    }
                }

                method == "POST" && path == "/links/delete" -> {
                    val params = parseForm(body)
                    savedLinksManager.deleteLink(params["id"].orEmpty())
                    syncManager.scheduleCloudSyncAfterServerMutation()
                    sendRedirect(s, 303, "/?msg=${urlEnc("Deleted link.")}")
                }

                method == "POST" && path == "/settings/folder" -> {
                    val params = parseForm(body)
                    driveSettings.setFolderId(params["folderId"].orEmpty())
                    sendRedirect(s, 303, "/?msg=${urlEnc("Saved Google Drive Folder ID.")}")
                }

                else -> {
                    val queryParams = parseForm(query)
                    sendHtml(s, buildDashboardHtml(queryParams["msg"], queryParams["err"]))
                }
            }
        }
    }

    private fun readHeaderBytes(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matchState = 0
        while (out.size() < 32_768) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            matchState = when {
                matchState == 0 && b == '\r'.code -> 1
                matchState == 1 && b == '\n'.code -> 2
                matchState == 2 && b == '\r'.code -> 3
                matchState == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readFixedBytes(input: BufferedInputStream, length: Int): String {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return String(buf, 0, offset, StandardCharsets.UTF_8)
    }

    private fun parseForm(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                result[k] = v
            }
        }
        return result
    }

    private fun sendRedirect(socket: Socket, status: Int, location: String) {
        val statusText = if (status == 302) "Found" else "See Other"
        val headers = "HTTP/1.1 $status $statusText\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
    }

    private fun sendJson(socket: Socket, status: Int, json: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "OK"
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtml(socket: Socket, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun buildDashboardHtml(msg: String?, err: String?): String {
        val isAuthed = authManager.isAuthenticated.value
        val hasAppData = authManager.hasAppDataScope.value
        val links = savedLinksManager.links.value
        val folderId = driveSettings.folderId.value

        val bannerHtml = buildString {
            if (!msg.isNullOrBlank()) {
                append("""<div class="banner ok">${esc(msg)}</div>""")
            }
            if (!err.isNullOrBlank()) {
                append("""<div class="banner err">${esc(err)}</div>""")
            }
        }

        val bookmarkletJs =
            "javascript:(function(){var u=location.href,c='';try{c=new URL(u).searchParams.get('code')||'';}catch(e){}if(!c&&window.performance&&performance.getEntries){var es=performance.getEntries();for(var i=0;i<es.length;i++){if(es[i].name&&es[i].name.indexOf('code=')>=0){try{c=new URL(es[i].name).searchParams.get('code')||'';if(c)break;}catch(e){}}}}var raw=c||prompt('Desktop Chrome blocked the custom oauthredirect scheme.\\nOpen DevTools (F12) -> Console (or Network with Preserve log), copy the oauthredirect?code=... URL or code= value, and paste here:');if(raw){var code=raw.trim();try{if(code.indexOf('code=')>=0){var q=code.indexOf('?')>=0?code.split('?')[1]:code;code=new URLSearchParams(q).get('code')||code;}}catch(e){}prompt('Copy this extracted code & paste on the DriveStream Companion page:',code);}})();"

        val cloneSectionHtml = """
            <div class="subcard">
                <p class="hint" style="margin:0 0 8px"><strong>Option 3 &mdash; Clone Login from Another DriveStream TV on LAN</strong> (calls <code>GET /api/tokens</code>):</p>
                <form method="post" action="/oauth/clone" novalidate onsubmit="return validateFormInput(this, 'peer', 'Enter the IP of a signed-in DriveStream TV (e.g. 192.168.0.190) first.');">
                    <input name="peer" placeholder="Peer TV IP (e.g. 192.168.0.190)" />
                    <button type="submit" class="btn">Clone Login from Peer TV</button>
                </form>
            </div>
        """.trimIndent()

        val authSection = if (isAuthed) {
            val cloudHint = if (hasAppData) {
                """<p class="status ok">Google Drive Cloud Sync: Enabled</p>"""
            } else {
                """<p class="hint">Cloud Sync (appDataFolder) not granted yet. Click below to re-authorize with Cloud Sync.</p>
                <a class="btn primary" href="/oauth/start" target="_blank" rel="noopener">Enable Cloud Sync</a>
                <form method="post" action="/oauth/complete" novalidate onsubmit="return validateFormInput(this, 'code', 'Paste the redirect URL, authorization code, or refresh token (1//...) first.');" style="margin-top:10px;margin-bottom:10px">
                    <input name="code" placeholder="Paste redirect URL, code=..., or refresh token (1//...)" />
                    <button type="submit" class="btn primary">Complete Cloud Sync Auth</button>
                </form>"""
            }
            """
            <p class="status ok">Google Drive: Signed In</p>
            $cloudHint
            <p class="hint">LAN token sync endpoints for other TVs: <code>GET /api/tokens</code> &bull; <code>POST /api/tokens</code></p>
            <form method="post" action="/oauth/signout">
                <button type="submit" class="btn danger">Sign Out</button>
            </form>
            """
        } else {
            """
            <p class="status">Google Drive: Not Signed In</p>
            <p class="hint"><strong>Recommended on Shield / Android TV:</strong> Use the <em>Sign In Using Built-In Browser (Shield / TV)</em> button right on the TV screen &mdash; it captures the OAuth redirect automatically.<br><br>
            <strong>Option 1 &mdash; Desktop Browser OAuth (Chrome / Edge hide custom URLs):</strong><br>
            1. Click <strong>Open Google Sign-In</strong> below (opens a new tab).<br>
            2. Before clicking <em>Continue</em> on Google, press <strong>F12 &rarr; Network</strong> and check <strong>Preserve log</strong> (or open <strong>F12 &rarr; Console</strong>).<br>
            3. After clicking <em>Continue</em> (even if the tab jumps to <code>www.google.com</code>), look in Console for <code>Failed to launch 'com.googleusercontent.apps...:/oauthredirect?code=...'</code> or in Network for <code>oauthredirect?code=...</code>, copy the URL or <code>code=</code> value, and paste below.<br>
            4. Helper bookmarklet (drag to bookmarks bar): <a class="btn" style="padding:4px 10px;font-size:12px" href="${esc(bookmarkletJs)}">Get DriveStream Code</a></p>
            <a class="btn primary" href="/oauth/start" target="_blank" rel="noopener">Open Google Sign-In</a>
            <div class="subcard">
                <p class="hint" style="margin:0 0 8px"><strong>Option 2 &mdash; Paste Redirect URL, Code, JSON, or Refresh Token (<code>1//...</code>):</strong></p>
                <form method="post" action="/oauth/complete" novalidate onsubmit="return validateFormInput(this, 'code', 'Please paste a redirect URL, authorization code, or refresh token (1//...) before clicking Complete Sign-In.');">
                    <input name="code" placeholder="Paste redirect URL, code=..., or refresh_token (1//...)" />
                    <button type="submit" class="btn primary">Complete Sign-In</button>
                </form>
            </div>
            $cloneSectionHtml
            """
        }

        val linksHtml = if (links.isEmpty()) {
            """<p class="hint">No saved links yet.</p>"""
        } else {
            links.joinToString("\n") { link ->
                """
                <div class="link-row">
                    <form method="post" action="/links/edit" class="row-form">
                        <input type="hidden" name="id" value="${esc(link.id)}" />
                        <input name="title" value="${esc(link.title)}" placeholder="Title" />
                        <input name="url" value="${esc(link.urlString)}" placeholder="URL" required />
                        <button type="submit" class="btn">Save</button>
                    </form>
                    <form method="post" action="/links/delete">
                        <input type="hidden" name="id" value="${esc(link.id)}" />
                        <button type="submit" class="btn danger">Delete</button>
                    </form>
                </div>
                """.trimIndent()
            }
        }

        return """
        <!doctype html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width,initial-scale=1" />
            <title>DriveStream TV Companion</title>
            <style>
                body{font-family:-apple-system,system-ui,sans-serif;background:#0a0a0f;color:#f4f4f8;margin:0;padding:20px;max-width:720px;margin-inline:auto}
                h1{font-size:24px;margin:0 0 16px}
                h2{font-size:16px;margin:0 0 10px;color:#93c5fd}
                .card{background:#151622;border:1px solid #26293d;border-radius:14px;padding:16px;margin-bottom:16px}
                .subcard{margin-top:14px;padding-top:12px;border-top:1px solid #242638}
                input{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:10px;border:1px solid #2d3148;background:#0d0e16;color:#fff;margin-bottom:8px;font-size:15px}
                .btn{display:inline-block;padding:10px 16px;border-radius:10px;border:0;background:#26293d;color:#fff;font-weight:600;font-size:14px;text-decoration:none;cursor:pointer}
                .btn.primary{background:#2563eb}
                .btn.danger{background:#991b1b}
                .hint{color:#9ca3af;font-size:13px;line-height:1.45}
                .banner{padding:12px;border-radius:10px;margin-bottom:14px;font-weight:600}
                .banner.ok{background:#14532d;color:#bbf7d0}
                .banner.err{background:#7f1d1d;color:#fecaca}
                .link-row{border-top:1px solid #242638;padding-top:10px;margin-top:10px}
                .row-form{display:grid;gap:6px;margin-bottom:6px}
                .status.ok{color:#4ade80;font-weight:600}
                code{background:#0d0e16;padding:2px 6px;border-radius:6px;word-break:break-all}
            </style>
            <script>
                function validateFormInput(form, fieldName, message) {
                    var el = form.querySelector('[name="' + fieldName + '"]');
                    var errBox = document.getElementById('clientErrBanner');
                    if (!el || !el.value || !el.value.trim()) {
                        if (errBox) {
                            errBox.textContent = message;
                            errBox.style.display = 'block';
                        } else {
                            alert(message);
                        }
                        if (el) el.focus();
                        return false;
                    }
                    el.value = el.value.trim();
                    if (errBox) errBox.style.display = 'none';
                    return true;
                }
            </script>
        </head>
        <body>
            <h1>DriveStream TV Companion</h1>
            $bannerHtml
            <div id="clientErrBanner" class="banner err" style="display:none"></div>
            <div class="card">
                <h2>Google Account (OAuth PKCE / Token Sync)</h2>
                $authSection
            </div>
            <div class="card">
                <h2>Add Web Link to TV</h2>
                <form method="post" action="/links/add">
                    <input name="title" placeholder="Title (optional)" />
                    <input name="url" placeholder="https://example.com" required />
                    <button type="submit" class="btn primary">Add Link</button>
                </form>
            </div>
            <div class="card">
                <h2>Saved Web Links (${links.size})</h2>
                $linksHtml
            </div>
            <div class="card">
                <h2>Google Drive Folder ID</h2>
                <form method="post" action="/settings/folder">
                    <input name="folderId" value="${esc(folderId)}" placeholder="Leave blank for all videos" />
                    <button type="submit" class="btn">Save Folder ID</button>
                </form>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
