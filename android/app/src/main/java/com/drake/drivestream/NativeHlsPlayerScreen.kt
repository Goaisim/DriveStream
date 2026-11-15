package com.drake.drivestream

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.focusable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NativeHlsStreamSpec(
    val m3u8Url: String,
    val title: String,
    val subtitle: String,
    val referer: String,
    val origin: String,
    val userAgent: String,
    val cookie: String,
    val extraHeaders: Map<String, String> = emptyMap()
)

private enum class NativeLiveEngine(val label: String) {
    EXOPLAYER("ExoPlayer"),
    LIBVLC("LibVLC")
}

private enum class LiveAspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL);

    fun next(): LiveAspectMode = when (this) {
        FIT -> ZOOM
        ZOOM -> FILL
        FILL -> FIT
    }
}

@OptIn(UnstableApi::class)
@Composable
fun NativeHlsPlayerScreen(
    stream: NativeHlsStreamSpec,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val proxy = remember(context) { LocalStreamProxy.getInstance(context).also { it.ensureStarted() } }
    val driveSettings = remember(context) { DriveSettings.getInstance(context) }

    var engineMode by remember { mutableStateOf(NativeLiveEngine.EXOPLAYER) }
    var aspectMode by remember { mutableStateOf(LiveAspectMode.FIT) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Starting ExoPlayer (60fps HLS)…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showHud by remember { mutableStateOf(true) }
    var hudInteractionTick by remember { mutableIntStateOf(0) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionFeedbackTick by remember { mutableIntStateOf(0) }

    var exoPlayerRef by remember { mutableStateOf<ExoPlayer?>(null) }
    var vlcPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var vlcLayoutRef by remember { mutableStateOf<VLCVideoLayout?>(null) }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    val dynamicHostCookies = remember(stream) { ConcurrentHashMap<String, String>() }
    val okHttpClient = remember(stream) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val reqUrl = req.url.toString()
                val host = req.url.host
                val webTargetCookie = runCatching { CookieManager.getInstance().getCookie(reqUrl) }.getOrNull()
                val webRefCookie = stream.referer.takeIf { it.isNotBlank() }?.let { ref ->
                    runCatching { CookieManager.getInstance().getCookie(ref) }.getOrNull()
                }
                val capturedSetCookie = dynamicHostCookies[host]
                val combinedCookie = mergeCookieHeaders(
                    stream.cookie,
                    webRefCookie,
                    webTargetCookie,
                    capturedSetCookie
                )
                val rebuilt = req.newBuilder().apply {
                    if (combinedCookie.isNotBlank()) {
                        header("Cookie", combinedCookie)
                    }
                    if (stream.referer.isNotBlank() && req.header("Referer").isNullOrBlank()) {
                        header("Referer", stream.referer)
                    }
                    if (stream.origin.isNotBlank() && req.header("Origin").isNullOrBlank()) {
                        header("Origin", stream.origin)
                    }
                    if (stream.userAgent.isNotBlank() && req.header("User-Agent").isNullOrBlank()) {
                        header("User-Agent", stream.userAgent)
                    }
                }.build()
                val response = chain.proceed(rebuilt)
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    val pairs = setCookies.map { it.substringBefore(';').trim() }.joinToString("; ")
                    dynamicHostCookies[host] = mergeCookieHeaders(dynamicHostCookies[host], pairs)
                }
                response
            }
            .build()
    }

    fun showFeedback(message: String) {
        actionFeedback = message
        actionFeedbackTick += 1
        hudInteractionTick += 1
    }

    fun togglePlayPause() {
        exoPlayerRef?.let { exo ->
            if (exo.isPlaying) {
                exo.pause()
                isPlaying = false
            } else {
                exo.play()
                isPlaying = true
            }
        }
        vlcPlayerRef?.let { vlc ->
            if (vlc.isPlaying) {
                vlc.pause()
                isPlaying = false
            } else {
                vlc.play()
                isPlaying = true
            }
        }
        hudInteractionTick += 1
    }

    fun goLive() {
        val exo = exoPlayerRef
        if (exo != null) {
            exo.seekToDefaultPosition()
            exo.play()
            showFeedback("Live Edge")
            return
        }
        reloadTick += 1
        showFeedback("Re-syncing Live")
    }

    fun cycleAspect() {
        val next = aspectMode.next()
        aspectMode = next
        vlcPlayerRef?.let { vlc ->
            when (next) {
                LiveAspectMode.FIT -> {
                    vlc.aspectRatio = null
                    vlc.scale = 0f
                }
                LiveAspectMode.ZOOM -> {
                    vlc.aspectRatio = "16:9"
                    vlc.scale = 1.25f
                }
                LiveAspectMode.FILL -> {
                    vlc.aspectRatio = "16:9"
                    vlc.scale = 0f
                }
            }
        }
        showFeedback("Aspect: ${next.label}")
    }

    fun switchEngine() {
        val next = if (engineMode == NativeLiveEngine.EXOPLAYER) {
            NativeLiveEngine.LIBVLC
        } else {
            NativeLiveEngine.EXOPLAYER
        }
        engineMode = next
        showFeedback("Engine: ${next.label}")
    }

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Own the active player strictly for the current (engineMode, stream, reloadTick) so old decoders release first.
    DisposableEffect(engineMode, stream, reloadTick) {
        isLoading = true
        isPlaying = false
        errorMessage = null

        if (engineMode == NativeLiveEngine.EXOPLAYER) {
            statusMessage = "Connecting ExoPlayer with headers…"
            val requestProps = LinkedHashMap<String, String>().apply {
                stream.extraHeaders.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) put(k, v)
                }
                if (stream.referer.isNotBlank()) put("Referer", stream.referer)
                if (stream.origin.isNotBlank()) put("Origin", stream.origin)
                if (stream.userAgent.isNotBlank()) put("User-Agent", stream.userAgent)
                val liveTargetCookie = runCatching { CookieManager.getInstance().getCookie(stream.m3u8Url) }.getOrNull()
                val liveRefCookie = stream.referer.takeIf { it.isNotBlank() }?.let { ref ->
                    runCatching { CookieManager.getInstance().getCookie(ref) }.getOrNull()
                }
                val cookie = mergeCookieHeaders(stream.cookie, liveRefCookie, liveTargetCookie)
                if (cookie.isNotBlank()) put("Cookie", cookie)
            }

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setDefaultRequestProperties(requestProps)

            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)

            val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(hlsFactory)
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (exoPlayerRef !== player) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            isLoading = true
                            statusMessage = "Buffering HLS stream…"
                        }
                        Player.STATE_READY -> {
                            isLoading = false
                            errorMessage = null
                            isPlaying = player.isPlaying || player.playWhenReady
                        }
                        Player.STATE_ENDED -> {
                            isPlaying = false
                        }
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    if (exoPlayerRef !== player) return
                    isPlaying = playing
                    if (playing) {
                        isLoading = false
                        errorMessage = null
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (exoPlayerRef !== player) return
                    isLoading = false
                    isPlaying = false
                    errorMessage = "ExoPlayer failed (${error.errorCodeName}). Switch to LibVLC or Retry."
                }
            }

            player.addListener(listener)
            exoPlayerRef = player
            val mediaItem = MediaItem.Builder()
                .setUri(stream.m3u8Url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            player.setMediaItem(mediaItem)
            player.playWhenReady = true
            player.prepare()

            onDispose {
                exoPlayerRef = null
                player.removeListener(listener)
                runCatching {
                    player.stop()
                    player.release()
                }
            }
        } else {
            statusMessage = "Connecting LibVLC HLS proxy…"
            val libVlc = LibVLC(
                context,
                arrayListOf(
                    "--network-caching=1000",
                    "--file-caching=1000",
                    "--clock-jitter=0",
                    "--aout=android_audiotrack",
                    "--no-sub-autodetect-file"
                )
            )
            val mediaPlayer = MediaPlayer(libVlc)
            var disposed = false

            mediaPlayer.setEventListener { event ->
                mainHandler.post {
                    if (disposed) return@post
                    when (event.type) {
                        MediaPlayer.Event.Opening -> statusMessage = "Opening LibVLC stream…"
                        MediaPlayer.Event.Buffering -> statusMessage = "Buffering ${event.buffering.toInt()}%…"
                        MediaPlayer.Event.Playing -> {
                            isLoading = false
                            isPlaying = true
                            errorMessage = null
                            runCatching {
                                mediaPlayer.setAudioDelay(driveSettings.audioDelayMs.value * 1000L)
                            }
                        }
                        MediaPlayer.Event.Paused -> isPlaying = false
                        MediaPlayer.Event.EndReached -> isPlaying = false
                        MediaPlayer.Event.EncounteredError -> {
                            isLoading = false
                            isPlaying = false
                            errorMessage = "LibVLC failed (${proxy.lastProxyStatus.value}). Switch to ExoPlayer or Retry."
                        }
                    }
                }
            }

            vlcPlayerRef = mediaPlayer
            vlcLayoutRef?.let { layout ->
                runCatching { mediaPlayer.attachViews(layout, null, false, false) }
            }

            val sid = proxy.registerHlsSession(
                referer = stream.referer,
                origin = stream.origin,
                userAgent = stream.userAgent,
                cookie = stream.cookie,
                extraHeaders = stream.extraHeaders
            )
            val proxyUri = Uri.parse(proxy.hlsStreamUrl(stream.m3u8Url, sid))
            val media = Media(libVlc, proxyUri).apply {
                addOption(":network-caching=1000")
                addOption(":clock-jitter=0")
                addOption(":no-sub-autodetect-file")
                if (stream.referer.isNotBlank()) addOption(":http-referrer=${stream.referer}")
                if (stream.userAgent.isNotBlank()) addOption(":http-user-agent=${stream.userAgent}")
            }
            mediaPlayer.media = media
            media.release()
            mediaPlayer.spuTrack = -1
            mediaPlayer.play()

            onDispose {
                disposed = true
                vlcPlayerRef = null
                mediaPlayer.setEventListener(null)
                runCatching {
                    mediaPlayer.stop()
                    mediaPlayer.detachViews()
                    mediaPlayer.release()
                    libVlc.release()
                }
            }
        }
    }

    LaunchedEffect(showHud, isPlaying, hudInteractionTick) {
        if (showHud && isPlaying) {
            delay(5_000L)
            showHud = false
        }
    }

    LaunchedEffect(showHud) {
        delay(40L)
        if (showHud) {
            runCatching { playPauseFocusRequester.requestFocus() }
        } else {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(actionFeedbackTick) {
        if (actionFeedbackTick > 0) {
            delay(1_100L)
            actionFeedback = null
        }
    }

    BackHandler(enabled = true) {
        if (showHud) {
            showHud = false
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        togglePlayPause()
                        showHud = true
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!showHud) {
                            showHud = true
                            hudInteractionTick += 1
                            true
                        } else {
                            hudInteractionTick += 1
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (engineMode == NativeLiveEngine.EXOPLAYER) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = aspectMode.resizeMode
                        player = exoPlayerRef
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayerRef) {
                        view.player = exoPlayerRef
                    }
                    view.resizeMode = aspectMode.resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        vlcLayoutRef = layout
                        vlcPlayerRef?.let { vlc ->
                            runCatching { vlc.attachViews(layout, null, false, false) }
                        }
                    }
                },
                update = { layout ->
                    if (vlcLayoutRef !== layout) {
                        vlcLayoutRef = layout
                        vlcPlayerRef?.let { vlc ->
                            runCatching { vlc.attachViews(layout, null, false, false) }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLoading && errorMessage == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA0A0A0F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF22C55E))
                Text(statusMessage, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp)
            }
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xDD141621), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(errorMessage.orEmpty(), color = Color(0xFFFCA5A5), fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerPillButton(label = "Retry", icon = Icons.Default.Refresh, isPrimary = true) {
                        reloadTick += 1
                    }
                    PlayerPillButton(
                        label = "Switch Engine",
                        icon = Icons.Default.SwapHoriz
                    ) {
                        switchEngine()
                    }
                }
            }
        }

        actionFeedback?.let { label ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.78f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.86f)
                            )
                        )
                    )
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close Live Player",
                        onClick = onClose
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stream.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF22C55E))
                            )
                            Text(
                                text = "LIVE • ${engineMode.label} • ${stream.subtitle}",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PlayerPillButton(
                            label = "Go Live",
                            icon = Icons.Default.LiveTv
                        ) {
                            goLive()
                        }
                        PlayerPillButton(
                            label = "Aspect: ${aspectMode.label}",
                            icon = Icons.Default.AspectRatio
                        ) {
                            cycleAspect()
                        }
                        PlayerPillButton(
                            label = "Engine: ${engineMode.label}",
                            icon = Icons.Default.SwapHoriz,
                            isPrimary = engineMode == NativeLiveEngine.EXOPLAYER
                        ) {
                            switchEngine()
                        }
                    }
                }

                // Bottom Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PlayerIconButton(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.focusRequester(playPauseFocusRequester),
                        isPrimary = true,
                        sizeDp = 60,
                        onClick = { togglePlayPause() }
                    )
                    Spacer(Modifier.width(18.dp))
                    PlayerPillButton(
                        label = "Go Live",
                        icon = Icons.Default.LiveTv
                    ) {
                        goLive()
                    }
                }
            }
        }
    }
}
