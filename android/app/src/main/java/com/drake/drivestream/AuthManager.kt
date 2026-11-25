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
import kotlinx.coroutines.delay
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
import java.io.File
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

private data class PersistedAuthData(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtSec: Long = 0L,
    val grantedScopes: String = ""
)

private class InvalidGrantException(message: String) : IOException(message)

class AuthManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val backupSessionFile = File(appContext.filesDir, "auth_session.json")
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val refreshMutex = Mutex()
    private var activeRefresh: CompletableDeferred<String>? = null

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _lastRefreshError = MutableStateFlow<String?>(null)
    val lastRefreshError: StateFlow<String?> = _lastRefreshError.asStateFlow()

    private val _hasAppDataScope = MutableStateFlow(false)
    val hasAppDataScope: StateFlow<Boolean> = _hasAppDataScope.asStateFlow()

    private val _hasDriveWriteScope = MutableStateFlow(false)
    val hasDriveWriteScope: StateFlow<Boolean> = _hasDriveWriteScope.asStateFlow()

    private val _tokenDiagnostics = MutableStateFlow("Session: Not Signed In | Access Token: None")
    val tokenDiagnostics: StateFlow<String> = _tokenDiagnostics.asStateFlow()

    private var companionServer: CompanionWebServer? = null

    private val _companionUrl = MutableStateFlow("http://${detectLocalIpAddress()}:$COMPANION_PORT")
    val companionUrl: StateFlow<String> = _companionUrl.asStateFlow()

    init {
        val restored = loadAndRepairStoredSession()
        _isAuthenticated.value = restored.refreshToken.isNotBlank()
        _hasAppDataScope.value = containsAppDataScope(restored.grantedScopes)
        _hasDriveWriteScope.value = containsDriveWriteScope(restored.grantedScopes)
        _tokenDiagnostics.value = buildDiagnostics(restored, _lastRefreshError.value)
    }

    fun currentRefreshToken(): String? =
        loadBestSession().refreshToken.trim().ifBlank { null }

    fun hasRefreshToken(): Boolean = currentRefreshToken() != null

    fun refreshDiagnostics() {
        val session = loadBestSession()
        _isAuthenticated.value = session.refreshToken.isNotBlank()
        _hasAppDataScope.value = containsAppDataScope(session.grantedScopes)
        _hasDriveWriteScope.value = containsDriveWriteScope(session.grantedScopes)
        _tokenDiagnostics.value = buildDiagnostics(session, _lastRefreshError.value)
    }

    fun markAppDataScopeRevoked() {
        val current = loadBestSession()
        persistSession(current.copy(grantedScopes = ""))
        _hasAppDataScope.value = false
        _hasDriveWriteScope.value = false
        refreshDiagnostics()
    }

    fun startCompanionServer() {
        refreshLocalIp()
        val existing = companionServer
        if (existing == null || !existing.isRunning()) {
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

    fun refreshIfNeededOnWakeAsync() {
        val session = loadBestSession()
        if (session.refreshToken.isBlank()) return
        val nowSec = System.currentTimeMillis() / 1000L
        if (session.accessToken.isNotBlank() && session.expiresAtSec - nowSec > 60L) {
            refreshDiagnostics()
            return
        }
        bgScope.launch {
            runCatching { refreshAccessToken() }
        }
    }

    fun createAuthSession(saveAsPending: Boolean = true): AuthSession {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        if (saveAsPending) {
            prefs.edit().putString(KEY_PENDING_VERIFIER, verifier).commit()
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
            _authError.value = "No system browser (Silk/Chrome) installed on this TV. Use Sign In from iPhone DriveStream above."
            return
        }

        val authUrl = startNewAuthSession()
        _isSigningIn.value = true
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _isSigningIn.value = false
            _authError.value = "Could not open TV system browser: ${e.message ?: "not available"}. Use iPhone push above."
        } catch (e: Exception) {
            _isSigningIn.value = false
            _authError.value = "Could not open TV system browser: ${e.message ?: "error"}. Use iPhone push above."
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
            val msg = "Paste a Session Code, JSON, redirect URL, or refresh token first."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val decodedSession = decodeSessionJson(trimmed)
        if (decodedSession != null && decodedSession.optString("refresh_token", "").isNotBlank()) {
            return importSessionJson(decodedSession)
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
            val msg = "No active PKCE session. Start Sign In with System Browser first."
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
            prefs.edit().remove(KEY_PENDING_VERIFIER).commit()
            _lastRefreshError.value = null
            _authError.value = null
            _isAuthenticated.value = hasRefreshToken()
            refreshDiagnostics()
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
                prefs.edit().remove(KEY_PENDING_VERIFIER).commit()
                _lastRefreshError.value = null
                _authError.value = null
                _isAuthenticated.value = hasRefreshToken()
                refreshDiagnostics()
                Result.success(Unit)
            } catch (e: InvalidGrantException) {
                val msg = "Google rejected token (invalid_grant): ${e.message ?: "revoked"}"
                _lastRefreshError.value = msg
                _authError.value = msg
                clearStoredCredentialsAndSignOut(clearErrors = false)
                Result.failure(e)
            } catch (e: Exception) {
                val msg = e.message ?: "Refresh token sign-in failed"
                _lastRefreshError.value = msg
                _authError.value = msg
                refreshDiagnostics()
                Result.failure(e)
            } finally {
                _isSigningIn.value = false
            }
        }

    suspend fun importSessionFromRawInput(rawBody: String): Result<Unit> {
        val obj = decodeSessionJson(rawBody)
            ?: return Result.failure(IllegalArgumentException("Invalid session JSON or base64 session code."))
        return importSessionJson(obj)
    }

    private suspend fun importSessionJson(obj: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        val refreshToken = obj.optString("refresh_token", "").trim()
        val accessToken = obj.optString("access_token", "").trim()
        val grantedScopes = obj.optString("granted_scopes", obj.optString("scope", "")).trim()
        val expiresAtSec = parseExpiresAtSeconds(obj)

        if (refreshToken.isEmpty()) {
            val msg = "Missing refresh_token in session payload."
            _authError.value = msg
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        // Immediately commit all three values synchronously to SharedPreferences + auth_session.json.
        persistSession(
            PersistedAuthData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtSec = expiresAtSec,
                grantedScopes = grantedScopes
            )
        )
        prefs.edit().remove(KEY_PENDING_VERIFIER).commit()
        _isAuthenticated.value = true
        _lastRefreshError.value = null
        _authError.value = null
        refreshDiagnostics()

        val verifyResult = signInWithRefreshToken(
            refreshToken = refreshToken,
            fallbackScopes = grantedScopes.ifBlank { null }
        )
        if (verifyResult.isSuccess) {
            return@withContext Result.success(Unit)
        }

        val err = verifyResult.exceptionOrNull()
        if (err is InvalidGrantException) {
            return@withContext Result.failure(err)
        }

        // If verification hit a transient network issue while a non-expired access_token was provided, stay signed in.
        val nowSec = System.currentTimeMillis() / 1000L
        if (accessToken.isNotEmpty() && expiresAtSec - nowSec > 60L) {
            _isAuthenticated.value = true
            _authError.value = null
            refreshDiagnostics()
            return@withContext Result.success(Unit)
        }

        Result.failure(err ?: IllegalStateException("Could not verify session with Google."))
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
        if (cleanRefresh.isEmpty()) {
            val msg = "Missing refresh_token in payload."
            _authError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val expiresAtSec = if (expirationMs > 0L) expirationMs / 1000L else 0L
        val obj = JSONObject().apply {
            put("access_token", cleanAccess)
            put("refresh_token", cleanRefresh)
            put("expires_at", expiresAtSec)
            if (cleanScopes.isNotEmpty()) put("granted_scopes", cleanScopes)
        }
        return importSessionJson(obj)
    }

    fun exportTokensJson(folderId: String): String {
        val session = loadBestSession()
        val obj = JSONObject().apply {
            put("authenticated", session.refreshToken.isNotBlank())
            put("client_id", CLIENT_ID)
            put("device_model", Build.MODEL.orEmpty())
            put("refresh_token", session.refreshToken)
            put("access_token", session.accessToken)
            put("expires_at", session.expiresAtSec)
            put("expiration_ms", session.expiresAtSec * 1000L)
            put("granted_scopes", session.grantedScopes)
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
            val res = importSessionJson(obj)
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
            val msg = "No active PKCE session. Start Sign In with System Browser first."
            _authError.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        return exchangeCodeForTokens(cleanCode, verifier)
    }

    fun getValidAccessToken(): String = runBlocking(Dispatchers.IO) {
        validAccessToken()
    }

    suspend fun validAccessToken(): String {
        val session = loadBestSession()
        val nowSec = System.currentTimeMillis() / 1000L
        if (session.accessToken.isNotBlank() && session.expiresAtSec - nowSec > 60L) {
            return session.accessToken
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
                val session = loadBestSession()
                val refreshToken = session.refreshToken.trim()
                if (refreshToken.isEmpty()) {
                    _lastRefreshError.value = "Missing refresh token"
                    clearStoredCredentialsAndSignOut(clearErrors = false)
                    throw IllegalStateException("Session expired. Please sign in from iPhone DriveStream.")
                }

                val form = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .build()

                var lastFailure: Exception = IOException("Token refresh failed")
                for (attempt in 1..3) {
                    if (attempt > 1) {
                        delay(2_000L)
                    }
                    try {
                        val tokenJson = executeTokenRequest(form)
                        saveTokens(tokenJson, fallbackRefreshToken = refreshToken)
                        _lastRefreshError.value = null
                        _authError.value = null
                        _isAuthenticated.value = hasRefreshToken()
                        refreshDiagnostics()
                        return@withContext tokenJson.getString("access_token")
                    } catch (ig: InvalidGrantException) {
                        _lastRefreshError.value = "Google revoked session (invalid_grant)"
                        _authError.value = "Google session expired or was revoked (invalid_grant). Please sign in again."
                        clearStoredCredentialsAndSignOut(clearErrors = false)
                        throw ig
                    } catch (e: Exception) {
                        lastFailure = e
                    }
                }

                // Transient error after 3 retries: preserve refresh_token and stay signed in.
                _lastRefreshError.value = lastFailure.message ?: "Network error refreshing token"
                _isAuthenticated.value = hasRefreshToken()
                refreshDiagnostics()
                throw lastFailure
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
        clearStoredCredentialsAndSignOut(clearErrors = true)
    }

    private fun clearStoredCredentialsAndSignOut(clearErrors: Boolean) {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_GRANTED_SCOPES)
            .remove(KEY_PENDING_VERIFIER)
            .commit()
        legacyPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(LEGACY_KEY_EXPIRATION_MS)
            .remove(KEY_GRANTED_SCOPES)
            .remove(KEY_PENDING_VERIFIER)
            .commit()
        runCatching {
            if (backupSessionFile.exists()) backupSessionFile.delete()
        }
        _isAuthenticated.value = false
        _hasAppDataScope.value = false
        _hasDriveWriteScope.value = false
        if (clearErrors) {
            _authError.value = null
            _lastRefreshError.value = null
        }
        refreshDiagnostics()
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
                if (response.code == 400 || response.code == 401) {
                    val errCode = runCatching { JSONObject(rawBody).optString("error", "") }.getOrDefault("")
                    if (errCode == "invalid_grant" || rawBody.contains("\"invalid_grant\"")) {
                        throw InvalidGrantException("Token error (${response.code}): $rawBody")
                    }
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
        val current = loadBestSession()
        val accessToken = json.getString("access_token")
        val expiresInSec = json.optLong("expires_in", 3600L)
        val expiresAtSec = (System.currentTimeMillis() / 1000L) + expiresInSec
        val refreshToken = json.optString("refresh_token", "")
            .trim()
            .ifBlank { fallbackRefreshToken?.trim().orEmpty() }
            .ifBlank { current.refreshToken }
        val scopeStr = json.optString("scope", "")
            .trim()
            .ifBlank { fallbackScopes?.trim().orEmpty() }
            .ifBlank { current.grantedScopes }

        persistSession(
            PersistedAuthData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtSec = expiresAtSec,
                grantedScopes = scopeStr
            )
        )
    }

    private fun persistSession(session: PersistedAuthData) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            if (session.refreshToken.isNotBlank()) {
                putString(KEY_REFRESH_TOKEN, session.refreshToken)
            }
            putLong(KEY_EXPIRES_AT, session.expiresAtSec)
            if (session.grantedScopes.isNotBlank()) {
                putString(KEY_GRANTED_SCOPES, session.grantedScopes)
            } else {
                remove(KEY_GRANTED_SCOPES)
            }
        }.commit()

        runCatching {
            val json = JSONObject().apply {
                put("access_token", session.accessToken)
                put("refresh_token", session.refreshToken)
                put("expires_at", session.expiresAtSec)
                put("granted_scopes", session.grantedScopes)
            }.toString()
            val tmp = File(appContext.filesDir, "auth_session.json.tmp")
            tmp.writeText(json, StandardCharsets.UTF_8)
            if (!tmp.renameTo(backupSessionFile)) {
                backupSessionFile.writeText(json, StandardCharsets.UTF_8)
                tmp.delete()
            }
        }

        _hasAppDataScope.value = containsAppDataScope(session.grantedScopes)
        _hasDriveWriteScope.value = containsDriveWriteScope(session.grantedScopes)
        _isAuthenticated.value = session.refreshToken.isNotBlank()
        _tokenDiagnostics.value = buildDiagnostics(session, _lastRefreshError.value)
    }

    private fun loadAndRepairStoredSession(): PersistedAuthData {
        val v2 = readFromPrefs(prefs, isLegacy = false)
        val fileSession = readFromBackupFile()
        val legacy = readFromPrefs(legacyPrefs, isLegacy = true)

        val candidates = listOfNotNull(v2, fileSession, legacy)
            .filter { it.refreshToken.isNotBlank() }

        val best = candidates.maxWithOrNull(
            compareBy<PersistedAuthData> { it.expiresAtSec }
                .thenBy { if (it.accessToken.isNotBlank()) 1 else 0 }
                .thenBy { if (it.grantedScopes.isNotBlank()) 1 else 0 }
        ) ?: v2

        if (best.refreshToken.isNotBlank()) {
            persistSession(best)
        }
        return best
    }

    private fun loadBestSession(): PersistedAuthData {
        val v2 = readFromPrefs(prefs, isLegacy = false)
        if (v2.refreshToken.isNotBlank()) return v2
        val fileSession = readFromBackupFile()
        if (fileSession != null && fileSession.refreshToken.isNotBlank()) {
            persistSession(fileSession)
            return fileSession
        }
        val legacy = readFromPrefs(legacyPrefs, isLegacy = true)
        if (legacy.refreshToken.isNotBlank()) {
            persistSession(legacy)
            return legacy
        }
        return v2
    }

    private fun readFromPrefs(targetPrefs: android.content.SharedPreferences, isLegacy: Boolean): PersistedAuthData {
        val access = targetPrefs.getString(KEY_ACCESS_TOKEN, "").orEmpty().trim()
        val refresh = targetPrefs.getString(KEY_REFRESH_TOKEN, "").orEmpty().trim()
        val expiresAtSec = if (isLegacy) {
            targetPrefs.getLong(LEGACY_KEY_EXPIRATION_MS, 0L) / 1000L
        } else {
            targetPrefs.getLong(KEY_EXPIRES_AT, 0L)
        }
        val scopes = targetPrefs.getString(KEY_GRANTED_SCOPES, "").orEmpty().trim()
        return PersistedAuthData(
            accessToken = access,
            refreshToken = refresh,
            expiresAtSec = expiresAtSec,
            grantedScopes = scopes
        )
    }

    private fun readFromBackupFile(): PersistedAuthData? = runCatching {
        if (!backupSessionFile.exists()) return null
        val obj = JSONObject(backupSessionFile.readText(StandardCharsets.UTF_8))
        PersistedAuthData(
            accessToken = obj.optString("access_token", "").trim(),
            refreshToken = obj.optString("refresh_token", "").trim(),
            expiresAtSec = parseExpiresAtSeconds(obj),
            grantedScopes = obj.optString("granted_scopes", "").trim()
        )
    }.getOrNull()

    private fun decodeSessionJson(rawInput: String): JSONObject? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{")) {
            val direct = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val wrapped = direct.optString("session", direct.optString("code", "")).trim()
            if (direct.optString("refresh_token", "").isBlank() && wrapped.isNotEmpty()) {
                return decodeSessionJson(wrapped)
            }
            return direct
        }

        val flagsList = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flags in flagsList) {
            val decoded = runCatching {
                String(Base64.decode(trimmed, flags), StandardCharsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrEmpty() && decoded.startsWith("{")) {
                val obj = runCatching { JSONObject(decoded) }.getOrNull()
                if (obj != null) return obj
            }
        }
        return null
    }

    private fun parseExpiresAtSeconds(obj: JSONObject): Long {
        val rawExpiresAt = obj.optLong("expires_at", 0L)
        if (rawExpiresAt > 10_000_000_000L) return rawExpiresAt / 1000L
        if (rawExpiresAt > 0L) return rawExpiresAt
        val rawExpMs = obj.optLong("expiration_ms", 0L)
        return if (rawExpMs > 0L) rawExpMs / 1000L else 0L
    }

    private fun buildDiagnostics(session: PersistedAuthData, lastError: String?): String {
        if (session.refreshToken.isBlank()) {
            return if (!lastError.isNullOrBlank()) {
                "Session: Signed Out | Last Error: $lastError"
            } else {
                "Session: Not Signed In | Access Token: None"
            }
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val accessPart = when {
            session.accessToken.isNotBlank() && session.expiresAtSec > nowSec -> {
                val mins = ((session.expiresAtSec - nowSec) / 60L).toInt()
                if (mins >= 1) "Valid (${mins}m left)" else "Valid (<1m left)"
            }
            session.accessToken.isNotBlank() -> "Expired (will auto-refresh)"
            else -> "Pending refresh"
        }

        val base = "Session: Refresh Token Saved ✓ | Access Token: $accessPart"
        return if (!lastError.isNullOrBlank()) "$base | Error: $lastError" else base
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
        const val DRIVE_WRITE_SCOPE = "https://www.googleapis.com/auth/drive"
        const val SCOPE = "https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.appdata"
        const val COMPANION_PORT = 8766

        private const val PREFS_NAME = "drivestream_auth_v2"
        private const val LEGACY_PREFS_NAME = "drivestream_auth"
        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val LEGACY_KEY_EXPIRATION_MS = "oauth_expiration_ms"
        private const val KEY_PENDING_VERIFIER = "pending_pkce_verifier"
        private const val KEY_GRANTED_SCOPES = "oauth_granted_scopes"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }

        private fun containsAppDataScope(scopes: String): Boolean =
            scopes.split(Regex("\\s+")).any { it == APPDATA_SCOPE || it.endsWith("/drive.appdata") }

        private fun containsDriveWriteScope(scopes: String): Boolean =
            scopes.split(Regex("\\s+")).any { it == DRIVE_WRITE_SCOPE || it.endsWith("/auth/drive") }

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

    fun isRunning(): Boolean = serverSocket?.let { !it.isClosed } == true

    fun start() {
        if (isRunning()) return
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
                method == "GET" && path == "/api/ping" -> {
                    val pingJson = JSONObject()
                        .put("app", "DriveStreamTV")
                        .put("signedIn", authManager.isAuthenticated.value)
                        .put("device", Build.MODEL.orEmpty())
                        .toString()
                    sendJson(s, 200, pingJson)
                }

                method == "POST" && path == "/api/import-session" -> {
                    val result = runCatching {
                        if (body.isBlank()) throw IllegalArgumentException("Empty session payload")
                        val res = runBlocking { authManager.importSessionFromRawInput(body) }
                        if (res.isFailure) {
                            throw res.exceptionOrNull() ?: IllegalStateException("Session import failed")
                        }
                        syncManager.triggerManualSync()
                        JSONObject().put("ok", true).toString()
                    }
                    if (result.isSuccess) {
                        sendJson(s, 200, result.getOrThrow())
                    } else {
                        val errJson = JSONObject()
                            .put("ok", false)
                            .put("error", result.exceptionOrNull()?.message ?: "Invalid session payload")
                            .toString()
                        sendJson(s, 400, errJson)
                    }
                }

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
                        sendRedirect(s, 303, "/?msg=${urlEnc("Added ${added.title} to TV.")}")
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
        authManager.refreshDiagnostics()
        val isAuthed = authManager.isAuthenticated.value
        val hasAppData = authManager.hasAppDataScope.value
        val diagnostics = authManager.tokenDiagnostics.value
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

        val pasteSessionHtml = """
            <div class="subcard">
                <p class="hint" style="margin:0 0 8px"><strong>Paste Session Token from iPhone DriveStream</strong><br>
                In iPhone DriveStream &rarr; <em>Settings &rarr; Sign In My TV</em>, tap <strong>Copy Session Code</strong> (or use 1-tap <strong>Push Login to TV</strong> directly from your iPhone).</p>
                <textarea id="sessionInput" rows="3" placeholder="Paste Session Code (base64) or JSON {&quot;access_token&quot;:...,&quot;refresh_token&quot;:...,&quot;expires_at&quot;:...}"></textarea>
                <button type="button" id="signInTvBtn" class="btn primary" onclick="submitSessionToken()">Sign In TV</button>
            </div>
        """.trimIndent()

        val cloneSectionHtml = """
            <div class="subcard">
                <p class="hint" style="margin:0 0 8px"><strong>Clone Login from Another DriveStream TV on LAN</strong> (calls <code>GET /api/tokens</code>):</p>
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
                """<p class="hint">Cloud Sync (appDataFolder) scope missing. Push a fresh login from iPhone DriveStream below to enable Cloud Sync.</p>"""
            }
            """
            <p class="status ok">Google Drive: Signed In</p>
            <p class="hint"><code>${esc(diagnostics)}</code></p>
            $cloudHint
            $pasteSessionHtml
            <p class="hint">LAN endpoints: <code>GET /api/ping</code> &bull; <code>POST /api/import-session</code> &bull; <code>GET /api/tokens</code></p>
            <form method="post" action="/oauth/signout" style="margin-top:10px">
                <button type="submit" class="btn danger">Sign Out</button>
            </form>
            """
        } else {
            """
            <p class="status">Google Drive: Not Signed In</p>
            <p class="hint"><code>${esc(diagnostics)}</code></p>
            $pasteSessionHtml
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
                input,textarea{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:10px;border:1px solid #2d3148;background:#0d0e16;color:#fff;margin-bottom:8px;font-size:15px;font-family:inherit}
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
                function showBanner(id, message) {
                    var el = document.getElementById(id);
                    if (el) { el.textContent = message; el.style.display = message ? 'block' : 'none'; }
                }
                function validateFormInput(form, fieldName, message) {
                    var el = form.querySelector('[name="' + fieldName + '"]');
                    if (!el || !el.value || !el.value.trim()) {
                        showBanner('clientErrBanner', message);
                        if (el) el.focus();
                        return false;
                    }
                    el.value = el.value.trim();
                    showBanner('clientErrBanner', '');
                    return true;
                }
                async function submitSessionToken() {
                    var input = document.getElementById('sessionInput');
                    var btn = document.getElementById('signInTvBtn');
                    var raw = (input ? input.value : '').trim();
                    showBanner('clientErrBanner', '');
                    showBanner('clientOkBanner', '');
                    if (!raw) {
                        showBanner('clientErrBanner', 'Paste the Session Code or JSON from iPhone DriveStream first.');
                        if (input) input.focus();
                        return;
                    }
                    var bodyStr = raw;
                    if (raw.charAt(0) !== '{') {
                        try {
                            var decoded = atob(raw.replace(/-/g, '+').replace(/_/g, '/'));
                            JSON.parse(decoded);
                            bodyStr = decoded;
                        } catch (e) {
                            bodyStr = JSON.stringify({ session: raw });
                        }
                    }
                    if (btn) { btn.disabled = true; btn.textContent = 'Signing In TV...'; }
                    try {
                        var resp = await fetch('/api/import-session', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: bodyStr
                        });
                        var data = await resp.json().catch(function() { return {}; });
                        if (resp.ok && data.ok) {
                            showBanner('clientOkBanner', 'TV Signed In! Loading your Drive library on TV...');
                            setTimeout(function() { window.location.href = '/?msg=' + encodeURIComponent('TV Signed In!'); }, 600);
                        } else {
                            showBanner('clientErrBanner', data.error || ('Sign-in failed (HTTP ' + resp.status + ')'));
                        }
                    } catch (err) {
                        showBanner('clientErrBanner', 'Network error: ' + err);
                    } finally {
                        if (btn) { btn.disabled = false; btn.textContent = 'Sign In TV'; }
                    }
                }
            </script>
        </head>
        <body>
            <h1>DriveStream TV Companion</h1>
            $bannerHtml
            <div id="clientOkBanner" class="banner ok" style="display:none"></div>
            <div id="clientErrBanner" class="banner err" style="display:none"></div>
            <div class="card">
                <h2>Google Account (iPhone Push / Session Sync)</h2>
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
