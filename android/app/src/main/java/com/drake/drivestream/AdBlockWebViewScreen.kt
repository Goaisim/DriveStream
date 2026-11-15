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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

    fun isBlockedRequest(uri: Uri): Boolean {
        val host = uri.host
        if (!host.isNullOrBlank() && isBlockedHost(host)) {
            return true
        }
        val lowerUrl = uri.toString().lowercase(Locale.US)
        for (pattern in BLOCKED_URL_SUBSTRINGS) {
            if (lowerUrl.contains(pattern)) {
                return true
            }
        }
        return false
    }

    fun isBlockedHost(host: String?): Boolean {
        val lower = host?.trim()?.lowercase(Locale.US) ?: return false
        if (lower.isEmpty()) return false
        val domains = blockedDomains
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

        private val BLOCKED_URL_SUBSTRINGS = listOf(
            "/ads/",
            "/adserver",
            "/popunder",
            "/pop.js",
            "banner.js",
            "prebid",
            "vast.xml"
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

private class AdBlockJsBridge(private val onBlocked: () -> Unit) {
    @JavascriptInterface
    fun reportBlock(kind: String) {
        onBlocked()
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

    var allowedRootDomain by remember {
        mutableStateOf(AndroidAdBlockEngine.rootDomain(Uri.parse(initialUrl).host))
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
    val webCursorFocusRequester = remember { FocusRequester() }

    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.rgb(10, 10, 15))
            isFocusable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgentString.replace("; wv", "")
            }

            addJavascriptInterface(
                AdBlockJsBridge { blockedCountFlow.update { it + 1 } },
                "AdBlockBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                    if ((scheme == "http" || scheme == "https") && engine.isBlockedRequest(uri)) {
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
                    if (scheme != "http" && scheme != "https") {
                        blockedCountFlow.update { it + 1 }
                        return true
                    }
                    if (engine.isBlockedRequest(uri)) {
                        blockedCountFlow.update { it + 1 }
                        return true
                    }
                    if (request?.isForMainFrame == true) {
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
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
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
        currentUrl = targetUrl
        addressText = targetUrl
        webView.loadUrl(targetUrl)
        webCursorFocusRequester.requestFocus()
    }

    fun toggleToolbar() {
        isToolbarVisible = !isToolbarVisible
        toolbarToggleTick += 1
    }

    fun dispatchVirtualTapAtCursor() {
        clickPulseActive = true
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 35L, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        mainHandler.postDelayed({ clickPulseActive = false }, 180L)
    }

    LaunchedEffect(Unit) {
        runCatching { webCursorFocusRequester.requestFocus() }
    }

    LaunchedEffect(toolbarToggleTick) {
        if (toolbarToggleTick == 0) return@LaunchedEffect
        if (isToolbarVisible) {
            delay(60L)
            runCatching { toolbarFocusRequester.requestFocus() }
        } else {
            runCatching { webCursorFocusRequester.requestFocus() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleBack?.let { mainHandler.removeCallbacks(it) }
            customVideoCallback?.onCustomViewHidden()
            customVideoCallback = null
            customVideoView = null
            webView.stopLoading()
            webView.removeJavascriptInterface("AdBlockBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    BackHandler(enabled = true) {
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
                val native = keyEvent.nativeKeyEvent
                if (keyEvent.type == KeyEventType.KeyDown && native.keyCode == KeyEvent.KEYCODE_MENU) {
                    toggleToolbar()
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = isToolbarVisible && customVideoView == null) {
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
                            onClick = { webView.reload() }
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
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (customVideoView != null) return@onPreviewKeyEvent false
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                        val native = keyEvent.nativeKeyEvent
                        val step = if (native.repeatCount >= 4) 52f else if (native.repeatCount >= 1) 34f else 22f
                        val edgeMargin = webHeightPx * 0.12f

                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                cursorY = (cursorY - step).coerceAtLeast(0f)
                                if (cursorY <= edgeMargin) {
                                    webView.scrollBy(0, -140)
                                }
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                cursorY = (cursorY + step).coerceAtMost(webHeightPx)
                                if (cursorY >= webHeightPx - edgeMargin) {
                                    webView.scrollBy(0, 140)
                                }
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                cursorX = (cursorX - step).coerceAtLeast(0f)
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                cursorX = (cursorX + step).coerceAtMost(webWidthPx)
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                dispatchVirtualTapAtCursor()
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )

                // On-screen pointer cursor overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
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

    // CSS Ad Hiding
    try {
        const styleEl = document.createElement('style');
        styleEl.textContent = '.ad, .ads, .adsbygoogle, [id^="google_ads"], [class*="popup"], [id*="popup"], iframe[src*="ads"], iframe[src*="doubleclick"] { display: none !important; }';
        (document.head || document.documentElement).appendChild(styleEl);
    } catch (e) {}

    // 1. Block window.open
    const blockedOpen = function() {
        reportBlock('window_open');
        return null;
    };
    try {
        Object.defineProperty(window, 'open', {
            value: blockedOpen,
            writable: false,
            configurable: false
        });
    } catch (e) {
        window.open = blockedOpen;
    }

    // 2. Neutralize window.alert & beforeunload traps
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

    // 3. Remove transparent click-hijack overlays & click Skip Ad
    function intersectsVideo(rect, videoRects) {
        for (let i = 0; i < videoRects.length; i++) {
            const v = videoRects[i];
            if (rect.left < v.right && rect.right > v.left && rect.top < v.bottom && rect.bottom > v.top) {
                return true;
            }
        }
        return false;
    }

    function isTransparentOverlay(el, style) {
        const opacity = parseFloat(style.opacity);
        if (!isNaN(opacity) && opacity < 0.05) {
            return true;
        }
        const bg = (style.backgroundColor || '').toLowerCase();
        const bgImage = style.backgroundImage || 'none';
        if ((bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)') && bgImage === 'none') {
            if (el.tagName === 'A') {
                return true;
            }
            const text = (el.textContent || '').trim();
            if (text === '' && !el.querySelector('button, input, select, textarea, svg, img, video')) {
                return true;
            }
        }
        return false;
    }

    const skipSelectors = [
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button',
        '.vast-skip-button',
        '.jw-skip',
        '[class*="skip-ad" i]',
        '[class*="skip_ad" i]',
        '[id*="skip-ad" i]',
        '[id*="skip_ad" i]',
        '[aria-label*="skip ad" i]'
    ].join(',');

    function sweepOverlaysAndSkipAds() {
        if (!document.documentElement) { return; }

        const videos = document.querySelectorAll('video');
        const videoRects = [];
        for (let i = 0; i < videos.length; i++) {
            const r = videos[i].getBoundingClientRect();
            if (r.width > 40 && r.height > 40) {
                videoRects.push(r);
            }
        }

        const vw = window.innerWidth || 0;
        const vh = window.innerHeight || 0;

        const candidates = document.querySelectorAll('div, a');
        for (let i = 0; i < candidates.length; i++) {
            const el = candidates[i];
            if (!el.isConnected || el.querySelector('video')) { continue; }
            const style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden' || style.pointerEvents === 'none') {
                continue;
            }
            const pos = style.position;
            if (pos !== 'fixed' && pos !== 'absolute') { continue; }
            const z = parseInt(style.zIndex, 10);
            if (isNaN(z) || z <= 999) { continue; }
            const rect = el.getBoundingClientRect();
            if (rect.width < 120 || rect.height < 120) { continue; }
            const coversPlayer = intersectsVideo(rect, videoRects) ||
                (vw > 0 && vh > 0 && rect.width >= vw * 0.45 && rect.height >= vh * 0.45);
            if (!coversPlayer) { continue; }
            if (isTransparentOverlay(el, style)) {
                el.remove();
                reportBlock('overlay');
            }
        }

        function tryClickSkip(btn) {
            if (!btn || btn.disabled || typeof btn.click !== 'function') { return; }
            if (btn.dataset && btn.dataset.dsSkipped === '1') { return; }
            const rect = btn.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
                if (btn.dataset) { btn.dataset.dsSkipped = '1'; }
                btn.click();
                reportBlock('skip_ad');
            }
        }

        const explicit = document.querySelectorAll(skipSelectors);
        for (let i = 0; i < explicit.length; i++) { tryClickSkip(explicit[i]); }

        const generic = document.querySelectorAll('button, [role="button"]');
        for (let i = 0; i < generic.length; i++) {
            const btn = generic[i];
            const label = (btn.innerText || btn.textContent || '').trim();
            if (/^skip(\s+ads?)?$/i.test(label)) { tryClickSkip(btn); }
        }
    }

    let domDirty = true;
    const observer = new MutationObserver(function() { domDirty = true; });
    function attachObserver() {
        const root = document.documentElement;
        if (!root) { return; }
        observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
        sweepOverlaysAndSkipAds();
    }
    if (document.documentElement) { attachObserver(); }
    else { document.addEventListener('DOMContentLoaded', attachObserver, { once: true }); }
    window.addEventListener('load', function() { domDirty = true; }, { once: true });
    setInterval(function() {
        if (domDirty) {
            domDirty = false;
            sweepOverlaysAndSkipAds();
        }
    }, 800);
})();
"""
