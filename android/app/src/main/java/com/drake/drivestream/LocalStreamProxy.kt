package com.drake.drivestream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
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
                    val statusCode = response.code
                    if (!response.isSuccessful && statusCode != 206) {
                        val msg = "Proxy :$PORT -> Drive HTTP $statusCode"
                        _lastProxyStatus.value = msg
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
                        "video/x-matroska"
                    }
                    val acceptRanges = response.header("Accept-Ranges") ?: "bytes"
                    val contentRange = response.header("Content-Range")
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                        ?: response.body?.contentLength()?.takeIf { it >= 0L }

                    val statusSize = formatTotalSize(contentRange, contentLength)
                    _lastProxyStatus.value = buildString {
                        append("Proxy :$PORT -> Drive HTTP $statusCode")
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

                    val stream = response.body?.byteStream() ?: return
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
            } catch (_: IOException) {
                // Client (LibVLC) frequently closes a range socket early when probing Cues/headers.
            } catch (e: Exception) {
                _lastProxyStatus.value = "Proxy :$PORT -> Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
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
