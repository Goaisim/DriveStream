package com.drake.drivestream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

// MARK: - Network-Level AdBlocker Engine (Built-in + Cached pgl.yoyo.org Host Set)

class AndroidAdBlockEngine private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var blockedDomains: Set<String> = BASE_BLOCKED_DOMAINS
    @Volatile
    private var refreshStarted = false

    init {
        loadCachedYoyo()
    }

    fun prepare() {
        if (refreshStarted) return
        refreshStarted = true
        val cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        val stale = !cacheFile.exists() ||
            (System.currentTimeMillis() - cacheFile.lastModified() > CACHE_TTL_MS)
        if (!stale) return

        scope.launch {
            runCatching {
                val req = Request.Builder().url(YOYO_LIST_URL).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string() ?: return@use
                    val parsed = parseYoyoDomains(text)
                    if (parsed.isNotEmpty()) {
                        cacheFile.writeText(text)
                        blockedDomains = BASE_BLOCKED_DOMAINS + parsed
                    }
                }
            }
        }
    }

    fun isBlockedHost(host: String?): Boolean = matchesDomainSet(host, blockedDomains)

    fun isBaseBlockedHost(host: String?): Boolean = matchesDomainSet(host, BASE_BLOCKED_DOMAINS)

    fun isStreamingOrPlayerUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme == "blob" || scheme == "data" || scheme == "ws" || scheme == "wss") {
            return true
        }
        val pathAndQuery = buildString {
            uri.encodedPath?.let { append(it) }
            uri.encodedQuery?.let {
                append('?')
                append(it)
            }
        }.lowercase(Locale.US)
        if (STREAMING_BYPASS_TOKENS.any { token -> pathAndQuery.contains(token) }) {
            return true
        }
        val hostLower = uri.host?.lowercase(Locale.US).orEmpty()
        return hostLower.contains("jwplayer") ||
            hostLower.contains("clappr") ||
            hostLower.contains("jwpcdn")
    }

    private fun matchesDomainSet(host: String?, domains: Set<String>): Boolean {
        val lower = host?.trim()?.lowercase(Locale.US) ?: return false
        if (lower.isEmpty()) return false
        if (lower in domains) return true
        var idx = lower.indexOf('.')
        while (idx >= 0 && idx + 1 < lower.length) {
            val parent = lower.substring(idx + 1)
            if (parent.contains('.') && parent in domains) {
                return true
            }
            idx = lower.indexOf('.', idx + 1)
        }
        return false
    }

    private fun loadCachedYoyo() {
        val cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return
        runCatching {
            val parsed = parseYoyoDomains(cacheFile.readText())
            if (parsed.isNotEmpty()) {
                blockedDomains = BASE_BLOCKED_DOMAINS + parsed
            }
        }
    }

    private fun parseYoyoDomains(text: String): Set<String> {
        val out = HashSet<String>(4096)
        text.lineSequence().forEach { raw ->
            val line = raw.trim().lowercase(Locale.US)
            if (line.isNotEmpty() && !line.startsWith("#") && line.contains('.') &&
                line.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
            ) {
                out.add(line)
            }
        }
        return out
    }

    companion object {
        private const val YOYO_LIST_URL =
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0"
        private const val CACHE_FILE_NAME = "yoyo_ad_domains.txt"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        val BASE_BLOCKED_DOMAINS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "juicyads.com",
            "onclickads.net",
            "outbrain.com",
            "taboola.com",
            "yandex.ru",
            "amazon-adsystem.com",
            "scorecardresearch.com",
            "zedo.com",
            "bidvertiser.com",
            "hilltopads.net",
            "trafficjunky.net",
            "mgid.com",
            "revcontent.com"
        )

        private val STREAMING_BYPASS_TOKENS = listOf(
            ".m3u8", ".ts", ".m4s", ".mp4", ".mpd", ".aac", ".key",
            "blob:", "data:", "ws:", "wss:",
            "jwplayer", "clappr", "hls.js", "video.js",
            "embed", "player", "stream"
        )

        private val MULTI_PART_PREFIXES = setOf(
            "co", "com", "net", "org", "edu", "gov", "ac", "ne", "or"
        )

        fun rootDomain(host: String?): String? {
            val clean = host?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return null
            if (clean.isEmpty()) return null
            if (clean == "localhost" || clean.contains(':') || clean.all { it.isDigit() || it == '.' }) {
                return clean
            }
            val parts = clean.split('.').filter { it.isNotEmpty() }
            if (parts.size < 2) return clean
            if (parts.size >= 3 && parts.last().length == 2 && parts[parts.size - 2] in MULTI_PART_PREFIXES) {
                return parts.takeLast(3).joinToString(".")
            }
            return parts.takeLast(2).joinToString(".")
        }

        @Volatile
        private var instance: AndroidAdBlockEngine? = null

        fun getInstance(context: Context): AndroidAdBlockEngine {
            return instance ?: synchronized(this) {
                instance ?: AndroidAdBlockEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}

private const val FIRE_TV_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 11; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private data class DetectedHlsStream(
    val m3u8Url: String,
    val referer: String,
    val origin: String,
    val userAgent: String,
    val cookie: String = "",
    val extraHeaders: Map<String, String> = emptyMap()
)

private val IGNORED_FORWARD_HEADERS = setOf(
    "host", "cookie", "referer", "origin", "user-agent",
    "connection", "content-length", "accept-encoding"
)

private fun filterExtraHeaders(raw: Map<String, String>?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((k, v) in raw) {
        val lower = k.lowercase(Locale.US)
        if (lower in IGNORED_FORWARD_HEADERS || lower.startsWith("sec-")) continue
        if (k.isNotBlank() && v.isNotBlank()) {
            out[k] = v
        }
    }
    return out
}

private fun deriveOrigin(referer: String): String {
    val refUri = runCatching { Uri.parse(referer) }.getOrNull() ?: return ""
    val refScheme = refUri.scheme
    val refHost = refUri.host
    if (refScheme.isNullOrBlank() || refHost.isNullOrBlank()) return ""
    val portSuffix = if (refUri.port > 0) ":${refUri.port}" else ""
    return "$refScheme://$refHost$portSuffix"
}

private fun looksLikeMasterPlaylist(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return lower.contains("master") || lower.contains("playlist") || lower.contains("index")
}

private fun looksLikeVariantOrAuxPlaylist(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return lower.contains("audio") || lower.contains("subtitle") ||
        lower.contains("caption") || lower.contains("chunklist") ||
        lower.contains("tracks-") || lower.contains("mono.m3u8")
}

private class AdBlockJsBridge(
    private val onBlocked: () -> Unit,
    private val onM3u8Detected: (DetectedHlsStream) -> Unit
) {
    @JavascriptInterface
    fun reportBlock(kind: String) {
        onBlocked()
    }

    @JavascriptInterface
    fun onM3u8Detected(json: String) {
        runCatching {
            val obj = JSONObject(json)
            val m3u8Url = obj.optString("m3u8Url", "").trim()
            if (!(m3u8Url.startsWith("http://", ignoreCase = true) ||
                    m3u8Url.startsWith("https://", ignoreCase = true))
            ) {
                return
            }
            onM3u8Detected(
                DetectedHlsStream(
                    m3u8Url = m3u8Url,
                    referer = obj.optString("referer", ""),
                    origin = obj.optString("origin", ""),
                    userAgent = obj.optString("userAgent", FIRE_TV_CHROME_UA)
                )
            )
        }
    }
}

// MARK: - Fire TV Ad-Blocked Web Browser Screen

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebViewScreen(
    initialUrl: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val engine = remember(context) { AndroidAdBlockEngine.getInstance(context).also { it.prepare() } }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val blockedCountFlow = remember { MutableStateFlow(0) }
    val strictLockFlow = remember { MutableStateFlow(true) }

    val blockedCount by blockedCountFlow.collectAsState()
    val strictRedirectLock by strictLockFlow.collectAsState()

    var addressText by remember { mutableStateOf(initialUrl) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var isAddressFocused by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.1f) }
    var isToolbarVisible by remember { mutableStateOf(true) }

    var customVideoView by remember { mutableStateOf<View?>(null) }
    var customVideoCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    var detectedStream by remember { mutableStateOf<DetectedHlsStream?>(null) }
    var nativeStreamSpec by remember { mutableStateOf<NativeHlsStreamSpec?>(null) }

    val latestMainFrameUrlRef = remember { AtomicReference(initialUrl) }

    var allowedRootDomain by remember {
        mutableStateOf(AndroidAdBlockEngine.rootDomain(Uri.parse(initialUrl).host))
    }

    fun updateDetectedStream(incoming: DetectedHlsStream) {
        val cleanUrl = incoming.m3u8Url.trim()
        if (!(cleanUrl.startsWith("http://", ignoreCase = true) ||
                cleanUrl.startsWith("https://", ignoreCase = true))
        ) {
            return
        }
        val candidate = incoming.copy(m3u8Url = cleanUrl)
        val existing = detectedStream
        if (existing == null) {
            detectedStream = candidate
            return
        }
        if (existing.m3u8Url == candidate.m3u8Url) {
            val mergedCookies = mergeCookieHeaders(existing.cookie, candidate.cookie)
            val bestReferer = candidate.referer.takeIf { it.isNotBlank() } ?: existing.referer
            val bestOrigin = candidate.origin.takeIf { it.isNotBlank() } ?: existing.origin
            val bestUa = candidate.userAgent.takeIf { it.isNotBlank() } ?: existing.userAgent
            val bestExtra = if (candidate.extraHeaders.isNotEmpty()) candidate.extraHeaders else existing.extraHeaders
            detectedStream = existing.copy(
                referer = bestReferer,
                origin = bestOrigin,
                userAgent = bestUa,
                cookie = mergedCookies,
                extraHeaders = bestExtra
            )
            return
        }
        val existingUri = runCatching { Uri.parse(existing.m3u8Url) }.getOrNull()
        val newUri = runCatching { Uri.parse(candidate.m3u8Url) }.getOrNull()
        val sameHost = existingUri?.host?.equals(newUri?.host, ignoreCase = true) == true
        val existingDir = existingUri?.path?.substringBeforeLast('/', "").orEmpty()
        val newDir = newUri?.path?.substringBeforeLast('/', "").orEmpty()
        val sameTree = sameHost && (newDir == existingDir || newDir.startsWith("$existingDir/"))
        if (sameTree &&
            !looksLikeVariantOrAuxPlaylist(existing.m3u8Url) &&
            (looksLikeMasterPlaylist(existing.m3u8Url) || looksLikeVariantOrAuxPlaylist(candidate.m3u8Url))
        ) {
            return
        }
        detectedStream = candidate
    }

    // Virtual mouse cursor coordinates inside the WebView container
    var webWidthPx by remember { mutableFloatStateOf(1920f) }
    var webHeightPx by remember { mutableFloatStateOf(960f) }
    var cursorX by remember { mutableFloatStateOf(960f) }
    var cursorY by remember { mutableFloatStateOf(480f) }
    var cursorInitialized by remember { mutableStateOf(false) }
    var clickPulseActive by remember { mutableStateOf(false) }

    var lastBackTapMs by remember { mutableStateOf(0L) }
    var pendingSingleBack by remember { mutableStateOf<Runnable?>(null) }
    var toolbarToggleTick by remember { mutableIntStateOf(0) }

    val toolbarFocusRequester = remember { FocusRequester() }
    val nativePlayerFocusRequester = remember { FocusRequester() }
    val webCursorFocusRequester = remember { FocusRequester() }

    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.rgb(10, 10, 15))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            keepScreenOn = true
            isFocusable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = FIRE_TV_CHROME_UA
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(
                AdBlockJsBridge(
                    onBlocked = { blockedCountFlow.update { it + 1 } },
                    onM3u8Detected = { stream ->
                        mainHandler.post { updateDetectedStream(stream) }
                    }
                ),
                "AdBlockBridge"
            )

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(this, AD_BLOCK_JS, setOf("*"))
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                    if (scheme != "http" && scheme != "https") {
                        return null
                    }

                    val urlStr = uri.toString()
                    if (urlStr.lowercase(Locale.US).contains(".m3u8") && !engine.isBlockedHost(uri.host)) {
                        val reqHeaders = request.requestHeaders ?: emptyMap()
                        val referer = reqHeaders["Referer"]
                            ?: reqHeaders["referer"]
                            ?: latestMainFrameUrlRef.get()
                        val origin = reqHeaders["Origin"]
                            ?: reqHeaders["origin"]
                            ?: deriveOrigin(referer)
                        val ua = reqHeaders["User-Agent"]
                            ?: reqHeaders["user-agent"]
                            ?: FIRE_TV_CHROME_UA
                        val reqCookie = reqHeaders["Cookie"] ?: reqHeaders["cookie"]
                        val targetCookie = runCatching { CookieManager.getInstance().getCookie(urlStr) }.getOrNull()
                        val refCookie = referer.takeIf { it.isNotBlank() }?.let { ref ->
                            runCatching { CookieManager.getInstance().getCookie(ref) }.getOrNull()
                        }
                        val mergedCookie = mergeCookieHeaders(reqCookie, refCookie, targetCookie)
                        val detected = DetectedHlsStream(
                            m3u8Url = urlStr,
                            referer = referer,
                            origin = origin,
                            userAgent = ua,
                            cookie = mergedCookie,
                            extraHeaders = filterExtraHeaders(reqHeaders)
                        )
                        mainHandler.post { updateDetectedStream(detected) }
                        return null
                    }

                    if (request.isForMainFrame) {
                        return null
                    }
                    val secFetchDest = (request.requestHeaders?.get("Sec-Fetch-Dest")
                        ?: request.requestHeaders?.get("sec-fetch-dest"))?.lowercase(Locale.US)
                    if (secFetchDest == "iframe" || secFetchDest == "video" || secFetchDest == "audio") {
                        if (secFetchDest == "iframe" && engine.isBaseBlockedHost(uri.host)) {
                            blockedCountFlow.update { it + 1 }
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                204,
                                "No Content",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return null
                    }
                    if (engine.isStreamingOrPlayerUri(uri)) {
                        return null
                    }
                    if (engine.isBlockedHost(uri.host)) {
                        blockedCountFlow.update { it + 1 }
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            204,
                            "No Content",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return true
                    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                    val isMainFrame = request.isForMainFrame
                    if (scheme != "http" && scheme != "https") {
                        if (!isMainFrame && (scheme == "about" || scheme == "blob" || scheme == "data")) {
                            return false
                        }
                        if (scheme == "blob" || scheme == "data" || scheme == "ws" || scheme == "wss") {
                            return false
                        }
                        blockedCountFlow.update { it + 1 }
                        return true
                    }
                    if (engine.isStreamingOrPlayerUri(uri)) {
                        if (isMainFrame) {
                            latestMainFrameUrlRef.set(uri.toString())
                        }
                        return false
                    }
                    if (!isMainFrame) {
                        if (engine.isBaseBlockedHost(uri.host)) {
                            blockedCountFlow.update { it + 1 }
                            return true
                        }
                        return false
                    }
                    if (engine.isBlockedHost(uri.host)) {
                        blockedCountFlow.update { it + 1 }
                        return true
                    }
                    val targetRoot = AndroidAdBlockEngine.rootDomain(uri.host)
                    if (strictLockFlow.value) {
                        val allowed = allowedRootDomain
                        if (allowed != null && targetRoot != null && targetRoot != allowed) {
                            blockedCountFlow.update { it + 1 }
                            return true
                        }
                    } else if (targetRoot != null) {
                        allowedRootDomain = targetRoot
                    }
                    latestMainFrameUrlRef.set(uri.toString())
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    if (!url.isNullOrBlank()) {
                        if (url != "about:blank") {
                            detectedStream = null
                            latestMainFrameUrlRef.set(url)
                        }
                        currentUrl = url
                        if (!isAddressFocused) {
                            addressText = url
                        }
                    }
                    canGoBack = view?.canGoBack() ?: false
                    canGoForward = view?.canGoForward() ?: false
                    view?.evaluateJavascript(AD_BLOCK_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    if (!url.isNullOrBlank()) {
                        currentUrl = url
                        if (!isAddressFocused) {
                            addressText = url
                        }
                    }
                    canGoBack = view?.canGoBack() ?: false
                    canGoForward = view?.canGoForward() ?: false
                    view?.evaluateJavascript(AD_BLOCK_JS, null)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    blockedCountFlow.update { it + 1 }
                    return false
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    blockedCountFlow.update { it + 1 }
                    result?.cancel()
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = (newProgress / 100f).coerceIn(0.05f, 1f)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customVideoCallback?.onCustomViewHidden()
                    (view.parent as? ViewGroup)?.removeView(view)
                    customVideoView = view
                    customVideoCallback = callback
                }

                override fun onHideCustomView() {
                    customVideoCallback?.onCustomViewHidden()
                    customVideoCallback = null
                    customVideoView = null
                }
            }

            loadUrl(initialUrl)
        }
    }

    fun navigateFromInput(rawInput: String) {
        val targetUrl = resolveAddressOrSearchUrl(rawInput) ?: return
        allowedRootDomain = AndroidAdBlockEngine.rootDomain(Uri.parse(targetUrl).host)
        detectedStream = null
        latestMainFrameUrlRef.set(targetUrl)
        currentUrl = targetUrl
        addressText = targetUrl
        webView.loadUrl(targetUrl)
        webCursorFocusRequester.requestFocus()
    }

    fun toggleToolbar() {
        isToolbarVisible = !isToolbarVisible
        toolbarToggleTick += 1
    }

    fun openNativePlayer(stream: DetectedHlsStream) {
        webView.evaluateJavascript(
            "document.querySelectorAll('video, audio').forEach(function(e){try{e.pause();}catch(_){}});",
            null
        )
        webView.onPause()
        val hostLabel = runCatching { Uri.parse(currentUrl).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: "Live Stream"
        val titleLabel = webView.title?.trim()?.takeIf { it.isNotBlank() }
            ?: hostLabel
        val liveTargetCookie = runCatching { CookieManager.getInstance().getCookie(stream.m3u8Url) }.getOrNull()
        val liveRefCookie = stream.referer.takeIf { it.isNotBlank() }?.let { ref ->
            runCatching { CookieManager.getInstance().getCookie(ref) }.getOrNull()
        }
        nativeStreamSpec = NativeHlsStreamSpec(
            m3u8Url = stream.m3u8Url,
            title = titleLabel,
            subtitle = hostLabel,
            referer = stream.referer,
            origin = stream.origin,
            userAgent = stream.userAgent.ifBlank { FIRE_TV_CHROME_UA },
            cookie = mergeCookieHeaders(stream.cookie, liveRefCookie, liveTargetCookie),
            extraHeaders = stream.extraHeaders
        )
    }

    fun closeNativePlayer() {
        nativeStreamSpec = null
        webView.onResume()
    }

    fun dispatchVirtualTapAtCursor() {
        clickPulseActive = true
        dispatchWebViewTap(webView, cursorX, cursorY)
        mainHandler.postDelayed({ clickPulseActive = false }, 180L)
    }

    LaunchedEffect(Unit) {
        runCatching { webCursorFocusRequester.requestFocus() }
    }

    LaunchedEffect(toolbarToggleTick) {
        if (toolbarToggleTick == 0) return@LaunchedEffect
        if (isToolbarVisible) {
            delay(60L)
            if (detectedStream != null) {
                runCatching { nativePlayerFocusRequester.requestFocus() }
            } else {
                runCatching { toolbarFocusRequester.requestFocus() }
            }
        } else {
            runCatching { webCursorFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(detectedStream != null) {
        if (detectedStream != null && nativeStreamSpec == null) {
            isToolbarVisible = true
        }
    }

    LaunchedEffect(nativeStreamSpec) {
        if (nativeStreamSpec == null) {
            delay(50L)
            runCatching { webCursorFocusRequester.requestFocus() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleBack?.let { mainHandler.removeCallbacks(it) }
            customVideoCallback?.onCustomViewHidden()
            customVideoCallback = null
            customVideoView = null
            webView.onResume()
            webView.stopLoading()
            webView.removeJavascriptInterface("AdBlockBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    BackHandler(enabled = nativeStreamSpec == null) {
        if (customVideoView != null) {
            customVideoCallback?.onCustomViewHidden()
            customVideoCallback = null
            customVideoView = null
            return@BackHandler
        }

        if (isAddressFocused) {
            focusManager.clearFocus()
            addressText = currentUrl
            return@BackHandler
        }

        val now = SystemClock.uptimeMillis()
        val pending = pendingSingleBack
        if (pending != null && now - lastBackTapMs <= 380L) {
            mainHandler.removeCallbacks(pending)
            pendingSingleBack = null
            lastBackTapMs = 0L
            toggleToolbar()
        } else {
            lastBackTapMs = now
            val runnable = Runnable {
                pendingSingleBack = null
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    onClose()
                }
            }
            pendingSingleBack = runnable
            mainHandler.postDelayed(runnable, 380L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .onPreviewKeyEvent { keyEvent ->
                if (nativeStreamSpec != null) return@onPreviewKeyEvent false
                val native = keyEvent.nativeKeyEvent
                if (keyEvent.type == KeyEventType.KeyDown) {
                    if (native.keyCode == KeyEvent.KEYCODE_MENU) {
                        toggleToolbar()
                        return@onPreviewKeyEvent true
                    }
                    val stream = detectedStream
                    if (stream != null && customVideoView == null &&
                        (native.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            native.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)
                    ) {
                        openNativePlayer(stream)
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = isToolbarVisible && customVideoView == null && nativeStreamSpec == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141621))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BrowserIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.focusRequester(toolbarFocusRequester),
                            onClick = onClose
                        )

                        BrowserIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            enabled = canGoBack,
                            onClick = { webView.goBack() }
                        )

                        BrowserIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            enabled = canGoForward,
                            onClick = { webView.goForward() }
                        )

                        BrowserIconButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            onClick = {
                                detectedStream = null
                                webView.reload()
                            }
                        )

                        // Editable Address/Search Bar
                        var urlBoxFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.opacity(0.08f))
                                .border(
                                    width = if (urlBoxFocused) 2.dp else 1.dp,
                                    color = if (urlBoxFocused) Color(0xFF3B82F6) else Color.White.opacity(0.14f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Lock else Icons.Default.Public,
                                contentDescription = null,
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = addressText,
                                onValueChange = { addressText = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                cursorBrush = SolidColor(Color(0xFF3B82F6)),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        focusManager.clearFocus()
                                        navigateFromInput(addressText)
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged {
                                        urlBoxFocused = it.isFocused
                                        isAddressFocused = it.isFocused
                                        if (!it.isFocused) {
                                            addressText = currentUrl
                                        }
                                    }
                            )
                        }

                        // Glowing Native Player button when .m3u8 live stream is detected
                        val activeStream = detectedStream
                        if (activeStream != null) {
                            var nativeBtnFocused by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .focusRequester(nativePlayerFocusRequester)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(21.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(Color(0xFF15803D), Color(0xFF22C55E))
                                        )
                                    )
                                    .border(
                                        width = if (nativeBtnFocused) 2.5.dp else 1.5.dp,
                                        color = if (nativeBtnFocused) Color.White else Color(0xFF86EFAC),
                                        shape = RoundedCornerShape(21.dp)
                                    )
                                    .onFocusChanged { nativeBtnFocused = it.isFocused }
                                    .focusable()
                                    .clickable { openNativePlayer(activeStream) }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Native Player (60fps HLS)",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Native Player (60fps HLS)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Strict Redirect Lock Toggle
                        var lockFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .background(
                                    if (strictRedirectLock) Color(0xFF1D4ED8).copy(alpha = 0.35f)
                                    else Color.White.opacity(0.08f)
                                )
                                .border(
                                    width = if (lockFocused) 2.5.dp else 1.dp,
                                    color = when {
                                        lockFocused -> Color.White
                                        strictRedirectLock -> Color(0xFF3B82F6)
                                        else -> Color.White.opacity(0.15f)
                                    },
                                    shape = RoundedCornerShape(21.dp)
                                )
                                .onFocusChanged { lockFocused = it.isFocused }
                                .focusable()
                                .clickable {
                                    val next = !strictLockFlow.value
                                    strictLockFlow.value = next
                                    if (next) {
                                        allowedRootDomain = AndroidAdBlockEngine.rootDomain(Uri.parse(currentUrl).host)
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (strictRedirectLock) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Strict Redirect Lock",
                                tint = if (strictRedirectLock) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (strictRedirectLock) "Lock ON" else "Lock OFF",
                                color = if (strictRedirectLock) Color(0xFF93C5FD) else Color(0xFF9CA3AF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Shield Counter Badge
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .background(Color.White.opacity(0.08f))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Blocked Ads",
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "$blockedCount",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = Color(0xFF3B82F6),
                            trackColor = Color.Transparent
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.opacity(0.08f))
                        )
                    }
                }
            }

            // WebView + Fire TV D-Pad Virtual Mouse Cursor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (size.width > 0 && size.height > 0) {
                            webWidthPx = size.width.toFloat()
                            webHeightPx = size.height.toFloat()
                            if (!cursorInitialized) {
                                cursorX = webWidthPx / 2f
                                cursorY = webHeightPx / 2f
                                cursorInitialized = true
                            } else {
                                cursorX = cursorX.coerceIn(0f, webWidthPx)
                                cursorY = cursorY.coerceIn(0f, webHeightPx)
                            }
                        }
                    }
                    .focusRequester(webCursorFocusRequester)
                    .focusable(enabled = customVideoView == null && nativeStreamSpec == null)
                    .onPreviewKeyEvent { keyEvent ->
                        if (customVideoView != null || nativeStreamSpec != null) return@onPreviewKeyEvent false
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        handleCursorDpadKey(
                            native = keyEvent.nativeKeyEvent,
                            webView = webView,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            webWidthPx = webWidthPx,
                            webHeightPx = webHeightPx,
                            onCursorMove = { nx, ny ->
                                cursorX = nx
                                cursorY = ny
                            },
                            onTap = { dispatchVirtualTapAtCursor() }
                        )
                    }
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )

                VirtualPointerOverlay(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    clickPulseActive = clickPulseActive,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Fullscreen HTML5 Video Overlay (WebChromeClient.onShowCustomView)
        val activeCustomView = customVideoView
        if (activeCustomView != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        (activeCustomView.parent as? ViewGroup)?.removeView(activeCustomView)
                        addView(
                            activeCustomView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        val activeNativeSpec = nativeStreamSpec
        if (activeNativeSpec != null) {
            NativeHlsPlayerScreen(
                stream = activeNativeSpec,
                onClose = { closeNativePlayer() }
            )
        }
    }
}

@Composable
private fun BrowserIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (isFocused) Color(0xFF3B82F6) else Color.White.opacity(0.09f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(enabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> Color.White.opacity(0.28f)
                isFocused -> Color.White
                else -> Color.White.opacity(0.92f)
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun Color.opacity(alpha: Float): Color = this.copy(alpha = alpha)

internal fun dispatchWebViewTap(webView: WebView, x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(downTime, downTime + 35L, MotionEvent.ACTION_UP, x, y, 0)
    webView.dispatchTouchEvent(down)
    webView.dispatchTouchEvent(up)
    down.recycle()
    up.recycle()
}

internal fun handleCursorDpadKey(
    native: KeyEvent,
    webView: WebView,
    cursorX: Float,
    cursorY: Float,
    webWidthPx: Float,
    webHeightPx: Float,
    allowTopEscape: Boolean = false,
    onCursorMove: (Float, Float) -> Unit,
    onTap: () -> Unit
): Boolean {
    val step = if (native.repeatCount >= 4) 52f else if (native.repeatCount >= 1) 34f else 22f
    val edgeMargin = webHeightPx * 0.12f

    return when (native.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
            if (allowTopEscape && cursorY <= 1f && webView.scrollY <= 0) {
                return false
            }
            val nextY = (cursorY - step).coerceAtLeast(0f)
            onCursorMove(cursorX, nextY)
            if (nextY <= edgeMargin) {
                webView.scrollBy(0, -140)
            }
            true
        }

        KeyEvent.KEYCODE_DPAD_DOWN -> {
            val nextY = (cursorY + step).coerceAtMost(webHeightPx)
            onCursorMove(cursorX, nextY)
            if (nextY >= webHeightPx - edgeMargin) {
                webView.scrollBy(0, 140)
            }
            true
        }

        KeyEvent.KEYCODE_DPAD_LEFT -> {
            onCursorMove((cursorX - step).coerceAtLeast(0f), cursorY)
            true
        }

        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            onCursorMove((cursorX + step).coerceAtMost(webWidthPx), cursorY)
            true
        }

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            onTap()
            true
        }

        else -> false
    }
}

@Composable
internal fun VirtualPointerOverlay(
    cursorX: Float,
    cursorY: Float,
    clickPulseActive: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val x = cursorX.coerceIn(0f, size.width)
        val y = cursorY.coerceIn(0f, size.height)

        if (clickPulseActive) {
            drawCircle(
                color = Color(0xFF60A5FA).copy(alpha = 0.55f),
                radius = 24f,
                center = Offset(x, y),
                style = Stroke(width = 4f)
            )
        }

        val pointer = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 32f)
            lineTo(x + 9f, y + 24f)
            lineTo(x + 17f, y + 38f)
            lineTo(x + 22f, y + 35f)
            lineTo(x + 14f, y + 21f)
            lineTo(x + 25f, y + 21f)
            close()
        }
        drawPath(path = pointer, color = Color.White)
        drawPath(
            path = pointer,
            color = Color(0xFF0A0A0F),
            style = Stroke(width = 3f)
        )
    }
}

private fun resolveAddressOrSearchUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase(Locale.US)
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        val uri = Uri.parse(trimmed)
        if (!uri.host.isNullOrBlank()) return uri.toString()
    }
    if (!trimmed.contains(' ') && (trimmed.contains('.') || lower.startsWith("localhost"))) {
        val withHttps = "https://$trimmed"
        val uri = Uri.parse(withHttps)
        if (!uri.host.isNullOrBlank()) return uri.toString()
    }
    val encoded = URLEncoder.encode(trimmed, "UTF-8")
    return "https://duckduckgo.com/?q=$encoded"
}

private const val AD_BLOCK_JS = """
(function() {
    if (window.__dsAdBlockInstalled) { return; }
    window.__dsAdBlockInstalled = true;

    function reportBlock(kind) {
        try {
            if (window.AdBlockBridge && window.AdBlockBridge.reportBlock) {
                window.AdBlockBridge.reportBlock(String(kind || 'js'));
            }
        } catch (e) {}
    }

    function reportM3u8(rawUrl) {
        try {
            if (!rawUrl || typeof rawUrl !== 'string') { return; }
            const trimmed = rawUrl.trim();
            if (!trimmed || trimmed.indexOf('blob:') === 0 || trimmed.indexOf('data:') === 0) { return; }
            const abs = new URL(trimmed, window.location.href).href;
            if (!/^https?:\/\//i.test(abs)) { return; }
            if (abs.toLowerCase().indexOf('.m3u8') === -1) { return; }
            if (window.AdBlockBridge && window.AdBlockBridge.onM3u8Detected) {
                window.AdBlockBridge.onM3u8Detected(JSON.stringify({
                    m3u8Url: abs,
                    referer: window.location.href || document.referrer || '',
                    origin: (window.location.origin && window.location.origin !== 'null') ? window.location.origin : '',
                    userAgent: navigator.userAgent || ''
                }));
            }
        } catch (e) {}
    }

    // 1. Strip restrictive iframe sandbox attributes & watch media src attributes (never touch window.open)
    function stripSandboxIframes(root) {
        try {
            if (root && root.nodeType === 1 && root.tagName === 'IFRAME' && root.hasAttribute('sandbox')) {
                root.removeAttribute('sandbox');
            }
            const scope = root && root.querySelectorAll ? root : document;
            const frames = scope.querySelectorAll('iframe[sandbox]');
            for (let i = 0; i < frames.length; i++) {
                frames[i].removeAttribute('sandbox');
            }
        } catch (e) {}
    }

    try {
        const origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(name, value) {
            if (this && this.tagName === 'IFRAME' && String(name).toLowerCase() === 'sandbox') {
                return;
            }
            if (this && (this.tagName === 'VIDEO' || this.tagName === 'SOURCE') &&
                String(name).toLowerCase() === 'src') {
                reportM3u8(value);
            }
            return origSetAttr.apply(this, arguments);
        };
    } catch (e) {}

    // 2. Detect .m3u8 requests via XHR, fetch, HTMLMediaElement.src, and native <video> loadstart
    try {
        const origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            if (typeof url === 'string') { reportM3u8(url); }
            else if (url && typeof url.toString === 'function') { reportM3u8(url.toString()); }
            return origOpen.apply(this, arguments);
        };
    } catch (e) {}

    try {
        if (typeof window.fetch === 'function') {
            const origFetch = window.fetch;
            window.fetch = function(input) {
                try {
                    const url = (typeof input === 'string')
                        ? input
                        : (input && input.url ? input.url : '');
                    reportM3u8(url);
                } catch (e) {}
                return origFetch.apply(this, arguments);
            };
        }
    } catch (e) {}

    try {
        const mediaSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
        if (mediaSrcDesc && mediaSrcDesc.configurable && typeof mediaSrcDesc.set === 'function') {
            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: true,
                enumerable: mediaSrcDesc.enumerable,
                get: mediaSrcDesc.get,
                set: function(val) {
                    if (typeof val === 'string') { reportM3u8(val); }
                    return mediaSrcDesc.set.call(this, val);
                }
            });
        }
    } catch (e) {}

    function scanMediaElements() {
        try {
            const nodes = document.querySelectorAll('video, source');
            for (let i = 0; i < nodes.length; i++) {
                reportM3u8(nodes[i].currentSrc || nodes[i].src);
            }
        } catch (e) {}
    }

    try {
        document.addEventListener('loadstart', function(ev) {
            const t = ev && ev.target;
            if (!t || t.tagName !== 'VIDEO') { return; }
            if (t.currentSrc) { reportM3u8(t.currentSrc); }
            if (t.src) { reportM3u8(t.src); }
            const srcEl = t.querySelector && t.querySelector('source[src]');
            if (srcEl && srcEl.src) { reportM3u8(srcEl.src); }
        }, true);
    } catch (e) {}

    // 3. Player-safe minimal CSS ad hiding
    try {
        const styleEl = document.createElement('style');
        styleEl.textContent = '.ad, .ads, .adsbygoogle, [id^="google_ads"], iframe[src*="ads"], iframe[src*="doubleclick"] { display: none !important; }';
        (document.head || document.documentElement).appendChild(styleEl);
    } catch (e) {}

    // 4. Neutralize alert & beforeunload traps (without altering window.open)
    try {
        Object.defineProperty(window, 'alert', {
            value: function() { reportBlock('alert'); },
            writable: false,
            configurable: false
        });
    } catch (e) {
        window.alert = function() { reportBlock('alert'); };
    }

    try {
        Object.defineProperty(window, 'onbeforeunload', {
            get: function() { return null; },
            set: function() { reportBlock('beforeunload'); },
            configurable: false
        });
    } catch (e) {
        window.onbeforeunload = null;
    }

    const origAddEventListener = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function(type, listener, options) {
        if (typeof type === 'string' && type.toLowerCase() === 'beforeunload') {
            reportBlock('beforeunload');
            return;
        }
        return origAddEventListener.call(this, type, listener, options);
    };

    // 5. Player-safe fake VPN banner remover (never touches players, iframes, or large wrappers)
    const PLAYER_SAFE_SELECTOR = 'video, iframe, canvas, .jwplayer, .jw-wrapper, [data-player], [id*="player" i], [class*="player" i], .clappr-player';

    function isPlayerProtected(el) {
        if (!el || el.nodeType !== 1) { return true; }
        if (el === document.documentElement || el === document.body || el.tagName === 'MAIN') {
            return true;
        }
        try {
            if (el.closest && el.closest(PLAYER_SAFE_SELECTOR)) { return true; }
            if (el.querySelector && el.querySelector(PLAYER_SAFE_SELECTOR)) { return true; }
        } catch (e) {
            return true;
        }
        return false;
    }

    function hideFakeVpnBanners() {
        if (!document.body) { return; }
        try {
            const nodes = document.body.querySelectorAll('div, section, aside, header, a, p, span');
            for (let i = 0; i < nodes.length; i++) {
                const el = nodes[i];
                if (!el.isConnected || (el.dataset && el.dataset.dsVpnHidden === '1')) { continue; }
                if (isPlayerProtected(el)) { continue; }
                const rawText = (el.innerText || '').trim();
                if (!rawText || rawText.length > 350) { continue; }
                const lower = rawText.toLowerCase();
                if (lower.indexOf('vpn recommended') === -1 &&
                    lower.indexOf('tap to install and continue watching') === -1) {
                    continue;
                }
                let target = el;
                while (target.parentElement) {
                    const parent = target.parentElement;
                    if (isPlayerProtected(parent)) { break; }
                    const pText = (parent.innerText || '').trim();
                    const pRect = parent.getBoundingClientRect();
                    if (pText.length > rawText.length + 12 || pRect.height > 220) { break; }
                    target = parent;
                }
                if (isPlayerProtected(target)) { continue; }
                if (target.dataset) { target.dataset.dsVpnHidden = '1'; }
                target.style.setProperty('display', 'none', 'important');
                reportBlock('vpn_banner');
            }
        } catch (e) {}
    }

    try {
        const sandboxObserver = new MutationObserver(function(mutations) {
            if (!mutations) { return; }
            for (let i = 0; i < mutations.length; i++) {
                const m = mutations[i];
                if (m.type === 'attributes' && m.target) {
                    stripSandboxIframes(m.target);
                } else if (m.addedNodes) {
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        stripSandboxIframes(m.addedNodes[j]);
                    }
                }
            }
        });
        const obsRoot = document.documentElement || document.body;
        if (obsRoot) {
            sandboxObserver.observe(obsRoot, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['sandbox']
            });
        }
    } catch (e) {}

    stripSandboxIframes(document);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            stripSandboxIframes(document);
            hideFakeVpnBanners();
            scanMediaElements();
        }, { once: true });
    } else {
        hideFakeVpnBanners();
        scanMediaElements();
    }
    window.addEventListener('load', function() {
        stripSandboxIframes(document);
        hideFakeVpnBanners();
        scanMediaElements();
    }, { once: true });
    setTimeout(function() { stripSandboxIframes(document); hideFakeVpnBanners(); scanMediaElements(); }, 600);
    setTimeout(function() { stripSandboxIframes(document); hideFakeVpnBanners(); scanMediaElements(); }, 1600);
    setTimeout(function() { stripSandboxIframes(document); hideFakeVpnBanners(); scanMediaElements(); }, 3500);
})();
"""
