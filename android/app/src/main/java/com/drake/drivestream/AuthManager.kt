package com.drake.drivestream

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    private var companionServer: CompanionWebServer? = null

    private val _companionUrl = MutableStateFlow("http://${detectLocalIpAddress()}:$COMPANION_PORT")
    val companionUrl: StateFlow<String> = _companionUrl.asStateFlow()

    private fun hasStoredAuth(): Boolean {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun startCompanionServer() {
        refreshLocalIp()
        if (companionServer == null) {
            companionServer = CompanionWebServer(
                authManager = this,
                savedLinksManager = SavedLinksManager.getInstance(appContext),
                driveSettings = DriveSettings.getInstance(appContext)
            ).also { it.start() }
        }
    }

    fun refreshLocalIp() {
        _companionUrl.value = "http://${detectLocalIpAddress()}:$COMPANION_PORT"
    }

    fun startNewAuthSession(): String {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        prefs.edit().putString(KEY_PENDING_VERIFIER, verifier).apply()
        _authError.value = null

        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
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
    }

    fun launchTvBrowserSignIn(context: Context) {
        val authUrl = startNewAuthSession()
        _isSigningIn.value = true
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _isSigningIn.value = false
            _authError.value = "No browser on TV (${e.localizedMessage}). Use ${_companionUrl.value} on your phone or PC."
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
            return Result.failure(IllegalArgumentException("Paste the redirect URL or authorization code."))
        }

        if (trimmed.contains("://") || trimmed.contains("code=") || trimmed.contains("error=")) {
            val parsable = if (trimmed.contains("://")) trimmed else "https://dummy.local/?${trimmed.trimStart('?')}"
            val uri = Uri.parse(parsable)
            val err = uri.getQueryParameter("error")
            if (!err.isNullOrBlank()) {
                val msg = "Google Sign-In error: $err"
                _authError.value = msg
                return Result.failure(IllegalStateException(msg))
            }
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank()) {
                return exchangeCode(code)
            }
        }

        return exchangeCode(trimmed)
    }

    private suspend fun exchangeCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        _isSigningIn.value = true
        _authError.value = null
        try {
            val verifier = prefs.getString(KEY_PENDING_VERIFIER, null)
            if (verifier.isNullOrBlank()) {
                throw IllegalStateException("No active PKCE session. Click 'Open Google Sign-In' first.")
            }

            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("code", code.trim())
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val tokenJson = executeTokenRequest(form)
            saveTokens(tokenJson)
            prefs.edit().remove(KEY_PENDING_VERIFIER).apply()
            _isAuthenticated.value = true
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
            .apply()
        _isAuthenticated.value = false
        _authError.value = null
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

    private fun saveTokens(json: JSONObject) {
        val accessToken = json.getString("access_token")
        val expiresInSec = json.optLong("expires_in", 3600L)
        val refreshToken = json.optString("refresh_token", "")

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken.isNotBlank()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            putLong(KEY_EXPIRATION_MS, System.currentTimeMillis() + expiresInSec * 1000L)
            apply()
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
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val COMPANION_PORT = 8766

        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_EXPIRATION_MS = "oauth_expiration_ms"
        private const val KEY_PENDING_VERIFIER = "pending_pkce_verifier"

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
    private val driveSettings: DriveSettings
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
                        contentLength = value.toIntOrNull()?.coerceIn(0, 262_144) ?: 0
                    }
                }
            }

            val body = if (contentLength > 0) readFixedBytes(input, contentLength) else ""

            when {
                method == "GET" && path == "/oauth/start" -> {
                    val authUrl = authManager.startNewAuthSession()
                    sendRedirect(s, 302, authUrl)
                }

                method == "POST" && path == "/oauth/complete" -> {
                    val params = parseForm(body)
                    val codeInput = params["code"].orEmpty()
                    val result = runBlocking { authManager.completeAuthFromInput(codeInput) }
                    if (result.isSuccess) {
                        sendRedirect(s, 303, "/?msg=${urlEnc("Signed in to Google Drive on TV!")}")
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Sign-in failed"
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
                        sendRedirect(s, 303, "/?msg=${urlEnc("Updated ${updated.title}.")}")
                    } else {
                        sendRedirect(s, 303, "/?err=${urlEnc("Invalid URL or link.")}")
                    }
                }

                method == "POST" && path == "/links/delete" -> {
                    val params = parseForm(body)
                    savedLinksManager.deleteLink(params["id"].orEmpty())
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

        val authSection = if (isAuthed) {
            """
            <p class="status ok">Google Drive: Signed In</p>
            <form method="post" action="/oauth/signout">
                <button type="submit" class="btn danger">Sign Out</button>
            </form>
            """
        } else {
            """
            <p class="status">Google Drive: Not Signed In</p>
            <p class="hint">1. Click below to authorize DriveStream with Google (opens in new tab).<br>
            2. Copy the redirect URL (<code>com.googleusercontent.apps...:/oauthredirect?code=...</code>) or <code>code</code> value from your browser address bar and paste it below.</p>
            <a class="btn primary" href="/oauth/start" target="_blank" rel="noopener">Open Google Sign-In</a>
            <form method="post" action="/oauth/complete" style="margin-top:12px">
                <input name="code" placeholder="Paste redirect URL or authorization code..." required />
                <button type="submit" class="btn primary">Complete Sign-In</button>
            </form>
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
        </head>
        <body>
            <h1>DriveStream Fire TV Companion</h1>
            $bannerHtml
            <div class="card">
                <h2>Google Account (OAuth PKCE)</h2>
                $authSection
            </div>
            <div class="card">
                <h2>Add Web Link to Fire TV</h2>
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
