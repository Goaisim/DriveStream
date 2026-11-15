package com.drake.drivestream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class LocalStreamProxy private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val _lastStatus = MutableStateFlow("Proxy idle")
    val lastStatus: StateFlow<String> = _lastStatus.asStateFlow()

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            ensureListening()
        }
    }

    fun streamUrl(fileId: String): String {
        val encoded = URLEncoder.encode(fileId, "UTF-8")
        return "http://127.0.0.1:$PORT/stream?fileId=$encoded"
    }

    private fun ensureListening() {
        if (serverSocket != null) return
        synchronized(this) {
            if (serverSocket != null) return
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", PORT))
                }
                serverSocket = socket
                _lastStatus.value = "Proxy listening :$PORT"
                scope.launch {
                    while (!socket.isClosed) {
                        try {
                            val clientConn = socket.accept()
                            launch { handleConnection(clientConn) }
                        } catch (_: IOException) {
                            break
                        }
                    }
                    serverSocket = null
                }
            } catch (e: Exception) {
                _lastStatus.value = "Proxy error: ${e.message}"
                serverSocket = null
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { conn ->
            conn.soTimeout = 30_000
            val input = BufferedInputStream(conn.getInputStream())
            val rawHeader = readHttpHeader(input) ?: return
            val headerText = String(rawHeader, StandardCharsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestParts = lines.firstOrNull()?.split(Regex("\\s+")) ?: return
            if (requestParts.size < 2) {
                sendSimpleError(conn, 400, "Malformed Request")
                return
            }

            val method = if (requestParts[0].equals("HEAD", ignoreCase = true)) "HEAD" else "GET"
            val rawTarget = requestParts[1]
            val fullUri = if (rawTarget.startsWith("http")) Uri.parse(rawTarget) else Uri.parse("http://127.0.0.1$rawTarget")
            if (fullUri.path != "/stream") {
                sendSimpleError(conn, 404, "Not Found")
                return
            }
            val fileId = fullUri.getQueryParameter("fileId")
            if (fileId.isNullOrBlank()) {
                sendSimpleError(conn, 400, "Missing fileId")
                return
            }

            var effectiveRange = "bytes=0-"
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    if (name == "range" && value.isNotEmpty()) {
                        effectiveRange = value
                    }
                }
            }

            val authManager = AuthManager.getInstance(appContext)
            val accessToken = try {
                runBlocking { authManager.validAccessToken() }
            } catch (e: Exception) {
                _lastStatus.value = "OAuth token error: ${e.message}"
                sendSimpleError(conn, 401, "OAuth Error")
                return
            }

            val targetUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&acknowledgeAbuse=true&supportsAllDrives=true"
            var upstreamResponse = executeFollowingRedirects(targetUrl, method, effectiveRange, accessToken)

            if (upstreamResponse.code == 401) {
                upstreamResponse.close()
                val refreshed = try {
                    runBlocking { authManager.refreshAccessToken() }
                } catch (e: Exception) {
                    sendSimpleError(conn, 401, "OAuth Refresh Failed")
                    return
                }
                upstreamResponse = executeFollowingRedirects(targetUrl, method, effectiveRange, refreshed)
            }

            upstreamResponse.use { resp ->
                val contentType = resp.header("Content-Type") ?: "video/x-matroska"
                val contentLength = resp.header("Content-Length")
                val contentRange = resp.header("Content-Range")
                val isPartial = resp.code == 206 || !contentRange.isNullOrEmpty()
                val statusReason = if (isPartial) "Partial Content" else if (resp.code == 200) "OK" else resp.message.ifBlank { "Error" }

                val headersOut = buildString {
                    append("HTTP/1.1 ${resp.code} $statusReason\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (!contentLength.isNullOrEmpty()) append("Content-Length: $contentLength\r\n")
                    if (!contentRange.isNullOrEmpty()) append("Content-Range: $contentRange\r\n")
                    append("Connection: close\r\n\r\n")
                }

                _lastStatus.value = "Drive HTTP ${resp.code} ($effectiveRange)"
                val out = conn.getOutputStream()
                out.write(headersOut.toByteArray(StandardCharsets.UTF_8))

                if (method != "HEAD") {
                    val bodyStream = resp.body?.byteStream()
                    if (bodyStream != null) {
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val read = bodyStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                out.flush()
            }
        }
    }

    private fun executeFollowingRedirects(
        startUrl: String,
        method: String,
        rangeHeader: String,
        accessToken: String
    ): Response {
        var currentUrl = startUrl
        var redirects = 0
        while (true) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("Accept-Encoding", "identity")
                .header("Range", rangeHeader)

            val host = Uri.parse(currentUrl).host?.lowercase().orEmpty()
            if (host.endsWith("googleapis.com") || host.endsWith("googleusercontent.com")) {
                reqBuilder.header("Authorization", "Bearer $accessToken")
            }

            if (method == "HEAD") {
                reqBuilder.head()
            } else {
                reqBuilder.get()
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (response.code in listOf(301, 302, 303, 307, 308) && redirects < 5) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IOException("Empty redirect Location")
                }
                currentUrl = location
                redirects += 1
                continue
            }
            return response
        }
    }

    private fun readHttpHeader(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 65_536) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun sendSimpleError(socket: Socket, status: Int, message: String) {
        runCatching {
            val body = message.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 $status $message\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        }
    }

    companion object {
        const val PORT = 8765

        @Volatile
        private var instance: LocalStreamProxy? = null

        fun getInstance(context: Context): LocalStreamProxy {
            return instance ?: synchronized(this) {
                instance ?: LocalStreamProxy(context.applicationContext).also { instance = it }
            }
        }
    }
}
