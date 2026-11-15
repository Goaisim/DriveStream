package com.drake.drivestream

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LocalStreamProxy private constructor(private val appContext: Context) {

    private val authManager = AuthManager.getInstance(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val serverLock = Any()
    private val connCounter = AtomicLong(0L)
    private val hlsCounter = AtomicLong(0L)

    data class HlsHeaderSession(
        val referer: String,
        val origin: String,
        val userAgent: String,
        val cookie: String = "",
        val extraHeaders: Map<String, String> = emptyMap()
    )

    private val hlsSessions = ConcurrentHashMap<String, HlsHeaderSession>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val _lastProxyStatus = MutableStateFlow("Proxy idle")
    val lastProxyStatus: StateFlow<String> = _lastProxyStatus.asStateFlow()
    val lastStatus: StateFlow<String> get() = lastProxyStatus

    fun start() {
        ensureStarted()
    }

    fun ensureStarted(): Boolean {
        synchronized(serverLock) {
            val current = serverSocket
            if (current != null && !current.isClosed) {
                return true
            }
            return try {
                val loopAddr = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(loopAddr, PORT), 64)
                }
                serverSocket = socket
                _lastProxyStatus.value = "Proxy :$PORT ready"
                Thread({ acceptLoop(socket) }, "DriveProxy-Accept").apply {
                    isDaemon = true
                    start()
                }
                true
            } catch (e: Exception) {
                _lastProxyStatus.value = "Proxy bind failed: ${e.message ?: e.javaClass.simpleName}"
                false
            }
        }
    }

    fun streamUrl(fileId: String): String {
        ensureStarted()
        return "http://127.0.0.1:$PORT/stream/${Uri.encode(fileId)}"
    }

    fun registerHlsSession(
        referer: String,
        origin: String,
        userAgent: String,
        cookie: String = "",
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val id = hlsCounter.incrementAndGet()
        val sid = id.toString()
        hlsSessions[sid] = HlsHeaderSession(
            referer = referer.trim(),
            origin = origin.trim(),
            userAgent = userAgent.trim(),
            cookie = cookie.trim(),
            extraHeaders = extraHeaders
        )
        if (hlsSessions.size > 32) {
            val minKeep = (id - 16).coerceAtLeast(0L)
            hlsSessions.keys.removeAll { key -> (key.toLongOrNull() ?: 0L) < minKeep }
        }
        return sid
    }

    fun hlsStreamUrl(targetUrl: String, sid: String): String {
        ensureStarted()
        return "http://127.0.0.1:$PORT/hls?sid=${Uri.encode(sid)}&u=${Uri.encode(targetUrl)}"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val clientConn = socket.accept()
                val id = connCounter.incrementAndGet()
                Thread({ handleConnection(clientConn) }, "DriveProxy-Conn-$id").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: IOException) {
                if (socket.isClosed) break
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    _lastProxyStatus.value = "Proxy accept error: ${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun handleConnection(clientConn: Socket) {
        clientConn.use { socket ->
            try {
                socket.soTimeout = 15_000
                socket.tcpNoDelay = true

                val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
                val headerText = readHttpHeaderBlock(input) ?: return
                val lines = headerText.split("\r\n")
                if (lines.isEmpty()) return

                val requestParts = lines[0].split(" ")
                if (requestParts.size < 2) return
                val method = requestParts[0].uppercase(Locale.US)
                val rawTarget = requestParts[1]
                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(socket.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                val headers = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim().lowercase(Locale.US)
                        val value = line.substring(colon + 1).trim()
                        headers[name] = value
                    }
                }

                if (rawTarget.startsWith("/hls")) {
                    handleHlsProxy(socket, method, rawTarget, headers)
                    return
                }

                val fileId = extractFileId(rawTarget)
                if (fileId.isNullOrBlank()) {
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request")
                    return
                }

                val token = try {
                    authManager.getValidAccessToken()
                } catch (e: Exception) {
                    _lastProxyStatus.value = "OAuth token error: ${e.message ?: "unauthorized"}"
                    writeSimpleResponse(socket.getOutputStream(), 401, "Unauthorized")
                    return
                }

                val rangeHeader = headers["range"]?.takeIf { it.isNotBlank() } ?: "bytes=0-"
                val driveUrl =
                    "https://www.googleapis.com/drive/v3/files/${Uri.encode(fileId)}?alt=media&acknowledgeAbuse=true&supportsAllDrives=true"

                val reqBuilder = Request.Builder()
                    .url(driveUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Range", rangeHeader)
                    .header("Accept-Encoding", "identity")
                if (method == "HEAD") {
                    reqBuilder.head()
                } else {
                    reqBuilder.get()
                }

                // Disable socket read timeout before long-lived stream transfer.
                socket.soTimeout = 0

                client.newCall(reqBuilder.build()).execute().use { response ->
                    forwardBinaryResponse(
                        socket = socket,
                        method = method,
                        response = response,
                        fallbackContentType = "video/x-matroska",
                        label = "Drive",
                        logContentSize = true
                    )
                }
            } catch (_: IOException) {
                // Client (LibVLC) frequently closes a range socket early when probing Cues/headers.
            } catch (e: Exception) {
                _lastProxyStatus.value = "Proxy :$PORT -> Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun handleHlsProxy(
        socket: Socket,
        method: String,
        rawTarget: String,
        headers: Map<String, String>
    ) {
        val parsed = runCatching { Uri.parse("http://localhost$rawTarget") }.getOrNull()
        val sid = parsed?.getQueryParameter("sid").orEmpty()
        val targetUrl = parsed?.getQueryParameter("u").orEmpty()
        if (targetUrl.isBlank() ||
            (!targetUrl.startsWith("http://", ignoreCase = true) &&
                !targetUrl.startsWith("https://", ignoreCase = true))
        ) {
            writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request")
            return
        }

        val session = hlsSessions[sid]
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("Accept-Encoding", "identity")

        headers["range"]?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Range", it) }
        session?.extraHeaders?.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                reqBuilder.header(k, v)
            }
        }
        session?.referer?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Referer", it) }
        session?.origin?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Origin", it) }
        session?.userAgent?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("User-Agent", it) }
        val refCookie = session?.referer?.takeIf { it.isNotBlank() }?.let { refUrl ->
            runCatching { CookieManager.getInstance().getCookie(refUrl) }.getOrNull()
        }
        val targetCookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
        val cookieHeader = mergeCookieHeaders(session?.cookie, refCookie, targetCookie)
        if (cookieHeader.isNotBlank()) {
            reqBuilder.header("Cookie", cookieHeader)
        }

        if (method == "HEAD") {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        socket.soTimeout = 0

        client.newCall(reqBuilder.build()).execute().use { response ->
            val statusCode = response.code
            if (!response.isSuccessful && statusCode != 206) {
                _lastProxyStatus.value = "Proxy :$PORT -> HLS HTTP $statusCode"
                writeSimpleResponse(socket.getOutputStream(), statusCode, response.message.ifEmpty { "Error" })
                return
            }

            val rawContentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            val finalUrl = response.request.url
            val finalUrlLower = finalUrl.toString().lowercase(Locale.US)
            val isExplicitM3u8 =
                rawContentType == "application/vnd.apple.mpegurl" ||
                    rawContentType == "application/x-mpegurl" ||
                    rawContentType == "audio/mpegurl" ||
                    rawContentType == "audio/x-mpegurl" ||
                    finalUrlLower.contains(".m3u8")

            if (method == "HEAD" && isExplicitM3u8) {
                _lastProxyStatus.value = "Proxy :$PORT -> HLS HTTP $statusCode"
                val headerBytes = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                val out = socket.getOutputStream()
                out.write(headerBytes)
                out.flush()
                return
            }

            val rawStream = response.body?.byteStream() ?: return
            val peekStream = BufferedInputStream(rawStream, 64 * 1024)
            var isPlaylist = isExplicitM3u8
            if (!isPlaylist &&
                headers["range"] == null &&
                (rawContentType.isNullOrBlank() ||
                    rawContentType.startsWith("text/") ||
                    rawContentType == "application/octet-stream")
            ) {
                peekStream.mark(64)
                val headBuf = ByteArray(48)
                val n = peekStream.read(headBuf)
                peekStream.reset()
                if (n > 0) {
                    val headText = String(headBuf, 0, n, StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    if (headText.startsWith("#EXTM3U")) {
                        isPlaylist = true
                    }
                }
            }

            if (isPlaylist) {
                val playlistText = String(peekStream.readBytes(), StandardCharsets.UTF_8)
                val rewritten = rewriteHlsPlaylist(playlistText, finalUrl, sid)
                val rewrittenBytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                _lastProxyStatus.value = "Proxy :$PORT -> HLS HTTP $statusCode"
                val headerBytes = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    append("Content-Length: ${rewrittenBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.US_ASCII)
                val out = socket.getOutputStream()
                out.write(headerBytes)
                out.write(rewrittenBytes)
                out.flush()
                return
            }

            forwardBinaryResponse(
                socket = socket,
                method = method,
                response = response,
                fallbackContentType = "video/mp2t",
                label = "HLS",
                logContentSize = false,
                bodyStreamOverride = peekStream
            )
        }
    }

    private fun rewriteHlsPlaylist(playlistText: String, baseUrl: HttpUrl, sid: String): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        return playlistText.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttrRegex.replace(line) { match ->
                    val rawUri = match.groupValues[1]
                    val abs = baseUrl.resolve(rawUri)?.toString() ?: rawUri
                    if (abs.startsWith("http://", ignoreCase = true) ||
                        abs.startsWith("https://", ignoreCase = true)
                    ) {
                        """URI="${hlsStreamUrl(abs, sid)}""""
                    } else {
                        match.value
                    }
                }
                else -> {
                    val abs = baseUrl.resolve(trimmed)?.toString() ?: trimmed
                    if (abs.startsWith("http://", ignoreCase = true) ||
                        abs.startsWith("https://", ignoreCase = true)
                    ) {
                        hlsStreamUrl(abs, sid)
                    } else {
                        trimmed
                    }
                }
            }
        }
    }

    private fun forwardBinaryResponse(
        socket: Socket,
        method: String,
        response: Response,
        fallbackContentType: String,
        label: String,
        logContentSize: Boolean,
        bodyStreamOverride: BufferedInputStream? = null
    ) {
        val statusCode = response.code
        if (!response.isSuccessful && statusCode != 206) {
            _lastProxyStatus.value = "Proxy :$PORT -> $label HTTP $statusCode"
            writeSimpleResponse(socket.getOutputStream(), statusCode, response.message.ifEmpty { "Error" })
            return
        }

        val rawContentType = response.header("Content-Type")?.substringBefore(';')?.trim()
        val contentType = if (
            !rawContentType.isNullOrBlank() &&
            !rawContentType.equals("application/octet-stream", ignoreCase = true)
        ) {
            rawContentType
        } else {
            fallbackContentType
        }
        val acceptRanges = response.header("Accept-Ranges") ?: "bytes"
        val contentRange = response.header("Content-Range")
        val contentLength = response.header("Content-Length")?.toLongOrNull()
            ?: response.body?.contentLength()?.takeIf { it >= 0L }

        val statusSize = if (logContentSize) formatTotalSize(contentRange, contentLength) else null
        _lastProxyStatus.value = buildString {
            append("Proxy :$PORT -> $label HTTP $statusCode")
            if (!statusSize.isNullOrBlank()) {
                append(" ($statusSize)")
            }
        }

        val statusReason = when (statusCode) {
            206 -> "Partial Content"
            200 -> "OK"
            else -> response.message.ifEmpty { "OK" }
        }
        val headerBytes = buildString {
            append("HTTP/1.1 $statusCode $statusReason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Accept-Ranges: $acceptRanges\r\n")
            if (contentLength != null && contentLength >= 0L) {
                append("Content-Length: $contentLength\r\n")
            }
            if (!contentRange.isNullOrBlank()) {
                append("Content-Range: $contentRange\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        val out = socket.getOutputStream()
        out.write(headerBytes)
        out.flush()

        if (method == "HEAD") return

        val stream = bodyStreamOverride ?: response.body?.byteStream() ?: return
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) {
                out.write(buffer, 0, read)
            }
        }
        out.flush()
    }

    private fun readHttpHeaderBlock(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream(1024)
        var prev1 = 0
        var prev2 = 0
        var prev3 = 0
        while (baos.size() < 32 * 1024) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return baos.toString(StandardCharsets.US_ASCII.name())
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return null
    }

    private fun extractFileId(rawTarget: String): String? {
        val parsed = runCatching { Uri.parse("http://localhost$rawTarget") }.getOrNull()
        val queryId = parsed?.getQueryParameter("id")
        if (!queryId.isNullOrBlank()) return queryId
        val path = parsed?.path ?: rawTarget.substringBefore('?')
        val cleaned = path.removePrefix("/stream/").removePrefix("/")
        return Uri.decode(cleaned).takeIf { it.isNotBlank() && !it.contains('/') }
    }

    private fun formatTotalSize(contentRange: String?, contentLength: Long?): String? {
        val totalBytes = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: contentLength
        if (totalBytes == null || totalBytes <= 0L) return null
        val mb = totalBytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun writeSimpleResponse(out: OutputStream, code: Int, reason: String) {
        val headers = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
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

internal fun mergeCookieHeaders(vararg rawHeaders: String?): String {
    val map = LinkedHashMap<String, String>()
    for (header in rawHeaders) {
        if (header.isNullOrBlank()) continue
        header.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    map[name] = value
                }
            }
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
