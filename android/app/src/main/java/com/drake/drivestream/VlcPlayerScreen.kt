package com.drake.drivestream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

data class PlayerEpisode(
    val id: String,
    val title: String,
    val shortTitle: String,
    val subtitle: String?,
    val episodeCode: String?,
    val stillUrl: String?,
    val streamUrl: String? = null,
    val referer: String = "",
    val origin: String = "",
    val userAgent: String = "",
    val isLiveStream: Boolean = false
)

private data class VlcTrackOption(
    val id: Int,
    val name: String
)

@Composable
fun VlcPlayerScreen(
    episodes: List<PlayerEpisode>,
    initialEpisode: PlayerEpisode,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val proxy = remember(context) { LocalStreamProxy.getInstance(context).also { it.ensureStarted() } }
    val proxyStatus by proxy.lastProxyStatus.collectAsState()
    val playbackStore = remember(context) { PlaybackStore.getInstance(context) }
    val syncManager = remember(context) { SyncManager.getInstance(context) }

    val queue = remember(episodes, initialEpisode) {
        episodes.ifEmpty { listOf(initialEpisode) }
    }

    var currentEpisode by remember { mutableStateOf(initialEpisode) }
    var isLoading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTimeSec by remember { mutableDoubleStateOf(0.0) }
    var durationSec by remember { mutableDoubleStateOf(0.0) }
    var statusMessage by remember { mutableStateOf("Opening stream…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var audioTracks by remember { mutableStateOf<List<VlcTrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<VlcTrackOption>>(emptyList()) }
    var currentAudioTrackId by remember { mutableIntStateOf(-1) }
    var currentSubtitleTrackId by remember { mutableIntStateOf(-1) }
    var preferredSubtitleTrackId by remember { mutableIntStateOf(-1) }

    var userToggledSubtitles by remember { mutableStateOf(false) }
    var didDisableInitialSubtitles by remember { mutableStateOf(false) }
    var didStartInitialPlayback by remember { mutableStateOf(false) }
    var isPlayerDisposed by remember { mutableStateOf(false) }
    var didAttemptResume by remember { mutableStateOf(false) }
    var seekIgnoreUntilMs by remember { mutableLongStateOf(0L) }
    var lastSavedAtMs by remember { mutableLongStateOf(0L) }

    var showHud by remember { mutableStateOf(true) }
    var hudInteractionTick by remember { mutableIntStateOf(0) }
    var skipFeedback by remember { mutableStateOf<String?>(null) }
    var skipFeedbackTick by remember { mutableIntStateOf(0) }

    var showEpisodeDrawer by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    val playerRootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    val libVlc = remember(context) {
        LibVLC(
            context,
            arrayListOf(
                "--network-caching=2000",
                "--no-sub-autodetect-file"
            )
        )
    }
    val mediaPlayer = remember(libVlc) { MediaPlayer(libVlc) }

    val currentIndex = queue.indexOfFirst { it.id == currentEpisode.id }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until (queue.size - 1)
    val selectableSubtitles = subtitleTracks.filter { it.id != -1 }

    fun saveProgress(force: Boolean) {
        if (currentEpisode.isLiveStream) return
        if (currentTimeSec <= 0.0 && !force) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAtMs < 5_000L) return
        lastSavedAtMs = now
        playbackStore.save(currentEpisode.id, currentTimeSec, durationSec)
    }

    fun persistAndSyncProgress() {
        if (currentEpisode.isLiveStream) return
        saveProgress(force = true)
        syncManager.scheduleSyncAfterChange()
    }

    fun seekTo(targetSec: Double) {
        if (!targetSec.isFinite()) return
        val upper = if (durationSec > 0.0) durationSec else maxOf(targetSec, currentTimeSec)
        val clamped = targetSec.coerceIn(0.0, upper)
        seekIgnoreUntilMs = System.currentTimeMillis() + 1_200L
        currentTimeSec = clamped
        val targetMs = (clamped * 1000.0).toLong().coerceAtLeast(0L)
        if (durationSec > 0.0) {
            mediaPlayer.position = (clamped / durationSec).toFloat()
        }
        mediaPlayer.time = targetMs
    }

    fun skipBy(deltaSeconds: Double) {
        seekTo(currentTimeSec + deltaSeconds)
        skipFeedback = if (deltaSeconds < 0) "${deltaSeconds.toInt()}s" else "+${deltaSeconds.toInt()}s"
        skipFeedbackTick += 1
        hudInteractionTick += 1
    }

    fun togglePlayPause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            isPlaying = false
        } else {
            mediaPlayer.play()
            isPlaying = true
        }
        hudInteractionTick += 1
    }

    fun refreshTracks() {
        val aTracks = mediaPlayer.audioTracks?.map { VlcTrackOption(it.id, it.name) }.orEmpty()
        audioTracks = aTracks
        currentAudioTrackId = mediaPlayer.audioTrack

        val sTracks = mediaPlayer.spuTracks?.map { VlcTrackOption(it.id, it.name) }.orEmpty()
        subtitleTracks = sTracks

        if (!userToggledSubtitles) {
            if (mediaPlayer.spuTrack != -1) {
                mediaPlayer.spuTrack = -1
            }
            currentSubtitleTrackId = -1
        } else {
            currentSubtitleTrackId = mediaPlayer.spuTrack
        }
    }

    fun toggleClosedCaptions() {
        userToggledSubtitles = true
        hudInteractionTick += 1
        if (currentSubtitleTrackId != -1) {
            preferredSubtitleTrackId = currentSubtitleTrackId
            mediaPlayer.spuTrack = -1
            currentSubtitleTrackId = -1
        } else {
            val target = selectableSubtitles.firstOrNull { it.id == preferredSubtitleTrackId }
                ?: selectableSubtitles.firstOrNull()
            if (target != null) {
                preferredSubtitleTrackId = target.id
                mediaPlayer.spuTrack = target.id
                currentSubtitleTrackId = target.id
            }
        }
    }

    fun startEpisode(episode: PlayerEpisode) {
        if (episode.id != currentEpisode.id) {
            saveProgress(force = true)
        }
        mediaPlayer.stop()
        currentEpisode = episode
        isLoading = true
        isPlaying = false
        currentTimeSec = 0.0
        durationSec = 0.0
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        currentAudioTrackId = -1
        currentSubtitleTrackId = -1
        preferredSubtitleTrackId = -1
        userToggledSubtitles = false
        didDisableInitialSubtitles = false
        didAttemptResume = false
        seekIgnoreUntilMs = 0L
        errorMessage = null
        val directUrl = episode.streamUrl?.trim()?.takeIf { it.isNotEmpty() }
        statusMessage = if (directUrl != null) "Connecting to live stream…" else "Connecting to Drive proxy…"

        if (!proxy.ensureStarted()) {
            isLoading = false
            errorMessage = proxy.lastProxyStatus.value
            return
        }

        val resolvedUrl = if (directUrl != null) {
            val sid = proxy.registerHlsSession(
                referer = episode.referer,
                origin = episode.origin,
                userAgent = episode.userAgent
            )
            proxy.hlsStreamUrl(directUrl, sid)
        } else {
            proxy.streamUrl(episode.id)
        }
        val streamUri = Uri.parse(resolvedUrl)
        val media = Media(libVlc, streamUri).apply {
            addOption(":network-caching=2000")
            addOption(":no-sub-autodetect-file")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.spuTrack = -1
        mediaPlayer.play()
    }

    // Keep TV screen awake during playback
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Register event listener once and release LibVLC cleanly on dispose.
    DisposableEffect(mediaPlayer) {
        isPlayerDisposed = false
        mediaPlayer.setEventListener { event ->
            mainHandler.post {
                if (isPlayerDisposed) return@post
                when (event.type) {
                    MediaPlayer.Event.Opening -> {
                        statusMessage = "Opening stream…"
                    }

                    MediaPlayer.Event.Buffering -> {
                        statusMessage = "Buffering ${event.buffering.toInt()}%…"
                    }

                    MediaPlayer.Event.Playing -> {
                        isPlaying = true
                        isLoading = false
                        errorMessage = null
                        if (!didDisableInitialSubtitles && !userToggledSubtitles) {
                            didDisableInitialSubtitles = true
                            mediaPlayer.spuTrack = -1
                            currentSubtitleTrackId = -1
                        }
                        refreshTracks()
                    }

                    MediaPlayer.Event.Paused -> {
                        isPlaying = false
                        persistAndSyncProgress()
                    }

                    MediaPlayer.Event.Vout, MediaPlayer.Event.ESAdded -> {
                        if (!didDisableInitialSubtitles && !userToggledSubtitles && mediaPlayer.spuTrack != -1) {
                            didDisableInitialSubtitles = true
                            mediaPlayer.spuTrack = -1
                            currentSubtitleTrackId = -1
                        }
                        refreshTracks()
                    }

                    MediaPlayer.Event.LengthChanged -> {
                        if (event.lengthChanged > 0) {
                            durationSec = event.lengthChanged / 1000.0
                        }
                    }

                    MediaPlayer.Event.TimeChanged -> {
                        if (event.timeChanged > 0L || mediaPlayer.time > 0L) {
                            isLoading = false
                        }
                        isPlaying = mediaPlayer.isPlaying
                        if (System.currentTimeMillis() >= seekIgnoreUntilMs) {
                            currentTimeSec = (event.timeChanged.coerceAtLeast(0L)) / 1000.0
                        }
                        val len = mediaPlayer.length
                        if (len > 0L) {
                            durationSec = len / 1000.0
                        }
                        if (!currentEpisode.isLiveStream && !didAttemptResume && durationSec > 0.0) {
                            didAttemptResume = true
                            val saved = playbackStore.progress(currentEpisode.id)
                            if (saved != null && saved.position > 1.0 && saved.position / durationSec < 0.95) {
                                seekTo(saved.position)
                            }
                        }
                        if (!didDisableInitialSubtitles && !userToggledSubtitles) {
                            didDisableInitialSubtitles = true
                            mediaPlayer.spuTrack = -1
                            currentSubtitleTrackId = -1
                        }
                        saveProgress(force = false)
                    }

                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        persistAndSyncProgress()
                        if (hasNext) {
                            startEpisode(queue[currentIndex + 1])
                        }
                    }

                    MediaPlayer.Event.EncounteredError -> {
                        isLoading = false
                        isPlaying = false
                        errorMessage = "Playback failed (${proxy.lastProxyStatus.value})."
                    }
                }
            }
        }

        onDispose {
            isPlayerDisposed = true
            persistAndSyncProgress()
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    // Auto-hide HUD after 5 seconds during active playback
    LaunchedEffect(showHud, isPlaying, hudInteractionTick, showEpisodeDrawer, showAudioDialog, showSubtitleDialog) {
        if (showHud && isPlaying && !showEpisodeDrawer && !showAudioDialog && !showSubtitleDialog) {
            delay(5_000L)
            showHud = false
        }
    }

    // Manage focus smoothly between player root and HUD play/pause button
    LaunchedEffect(showHud, showEpisodeDrawer, showAudioDialog, showSubtitleDialog) {
        if (!showEpisodeDrawer && !showAudioDialog && !showSubtitleDialog) {
            delay(40L)
            if (showHud) {
                runCatching { playPauseFocusRequester.requestFocus() }
            } else {
                runCatching { playerRootFocusRequester.requestFocus() }
            }
        }
    }

    // Clear skip feedback badge
    LaunchedEffect(skipFeedbackTick) {
        if (skipFeedbackTick > 0) {
            delay(1_100L)
            skipFeedback = null
        }
    }

    BackHandler(enabled = true) {
        when {
            showAudioDialog -> showAudioDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            showEpisodeDrawer -> showEpisodeDrawer = false
            showHud -> showHud = false
            else -> {
                saveProgress(force = true)
                onClose()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerRootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showEpisodeDrawer || showAudioDialog || showSubtitleDialog) return@onPreviewKeyEvent false

                val code = keyEvent.nativeKeyEvent.keyCode
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        togglePlayPause()
                        showHud = true
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        skipBy(-10.0)
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        skipBy(10.0)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!showHud) {
                            skipBy(-10.0)
                            true
                        } else {
                            hudInteractionTick += 1
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!showHud) {
                            skipBy(10.0)
                            true
                        } else {
                            hudInteractionTick += 1
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
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
        AndroidView(
            factory = { viewCtx ->
                VLCVideoLayout(viewCtx).also { layout ->
                    mediaPlayer.attachViews(layout, null, false, false)
                    if (!didStartInitialPlayback) {
                        didStartInitialPlayback = true
                        layout.post {
                            if (!isPlayerDisposed) {
                                startEpisode(initialEpisode)
                            }
                        }
                    }
                }
            },
            update = {},
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading && errorMessage == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA0A0A0F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF3B82F6))
                Text(statusMessage, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                Text(proxyStatus, color = Color(0xFF9CA3AF), fontSize = 12.sp)
            }
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC141621), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(errorMessage.orEmpty(), color = Color(0xFFFCA5A5), fontSize = 15.sp)
                PlayerPillButton(label = "Retry", isPrimary = true) {
                    startEpisode(currentEpisode)
                }
            }
        }

        skipFeedback?.let { label ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Player HUD Overlay
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
                                Color.Black.copy(alpha = 0.88f)
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
                        contentDescription = "Close Player",
                        onClick = {
                            saveProgress(force = true)
                            onClose()
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentEpisode.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        currentEpisode.subtitle?.let { sub ->
                            Text(
                                text = sub,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (queue.size > 1) {
                        PlayerPillButton(
                            label = "Episodes (${queue.size})",
                            icon = Icons.Default.List
                        ) {
                            showEpisodeDrawer = true
                        }
                    }
                }

                // Bottom Bar (Progress + D-Pad Controls)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val progressFraction = if (durationSec > 0.0) {
                        (currentTimeSec / durationSec).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF3B82F6),
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatDuration(currentTimeSec), color = Color.White, fontSize = 13.sp)
                            Text(
                                text = when {
                                    durationSec > 0.0 -> formatDuration(durationSec)
                                    currentEpisode.isLiveStream -> "LIVE"
                                    else -> "--:--"
                                },
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (queue.size > 1) {
                            PlayerIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Episode",
                                enabled = hasPrev,
                                onClick = {
                                    if (hasPrev) startEpisode(queue[currentIndex - 1])
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                        }

                        PlayerIconButton(
                            icon = Icons.Default.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            onClick = { skipBy(-10.0) }
                        )
                        Spacer(Modifier.width(16.dp))

                        PlayerIconButton(
                            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.focusRequester(playPauseFocusRequester),
                            isPrimary = true,
                            sizeDp = 60,
                            onClick = { togglePlayPause() }
                        )

                        Spacer(Modifier.width(16.dp))
                        PlayerIconButton(
                            icon = Icons.Default.Forward10,
                            contentDescription = "Forward 10 seconds",
                            onClick = { skipBy(10.0) }
                        )

                        if (queue.size > 1) {
                            Spacer(Modifier.width(12.dp))
                            PlayerIconButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = "Next Episode",
                                enabled = hasNext,
                                onClick = {
                                    if (hasNext) startEpisode(queue[currentIndex + 1])
                                }
                            )
                        }

                        Spacer(Modifier.width(24.dp))

                        PlayerPillButton(
                            label = if (currentSubtitleTrackId != -1) "CC ON" else "CC OFF",
                            icon = Icons.Default.ClosedCaption,
                            isPrimary = currentSubtitleTrackId != -1,
                            enabled = selectableSubtitles.isNotEmpty()
                        ) {
                            toggleClosedCaptions()
                        }

                        if (selectableSubtitles.size > 1) {
                            Spacer(Modifier.width(10.dp))
                            PlayerPillButton(
                                label = "Subtitles",
                                icon = Icons.Default.Subtitles
                            ) {
                                showSubtitleDialog = true
                            }
                        }

                        if (audioTracks.size > 1) {
                            Spacer(Modifier.width(10.dp))
                            PlayerPillButton(
                                label = "Audio",
                                icon = Icons.Default.Audiotrack
                            ) {
                                showAudioDialog = true
                            }
                        }
                    }
                }
            }
        }

        // Episode Switcher Overlay
        if (showEpisodeDrawer) {
            TrackOrEpisodeModal(
                title = "Episodes",
                onDismiss = { showEpisodeDrawer = false }
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(queue, key = { it.id }) { ep ->
                        val active = ep.id == currentEpisode.id
                        ModalOptionRow(
                            title = ep.shortTitle,
                            subtitle = ep.subtitle,
                            selected = active,
                            onClick = {
                                showEpisodeDrawer = false
                                if (!active) startEpisode(ep)
                            }
                        )
                    }
                }
            }
        }

        // Audio Track Picker
        if (showAudioDialog) {
            TrackOrEpisodeModal(
                title = "Audio Tracks",
                onDismiss = { showAudioDialog = false }
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(audioTracks, key = { it.id }) { track ->
                        ModalOptionRow(
                            title = track.name,
                            subtitle = null,
                            selected = track.id == currentAudioTrackId,
                            onClick = {
                                mediaPlayer.audioTrack = track.id
                                currentAudioTrackId = track.id
                                showAudioDialog = false
                            }
                        )
                    }
                }
            }
        }

        // Subtitle Track Picker
        if (showSubtitleDialog) {
            TrackOrEpisodeModal(
                title = "Subtitle Tracks",
                onDismiss = { showSubtitleDialog = false }
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "sub-off") {
                        ModalOptionRow(
                            title = "Off",
                            subtitle = null,
                            selected = currentSubtitleTrackId == -1,
                            onClick = {
                                userToggledSubtitles = true
                                mediaPlayer.spuTrack = -1
                                currentSubtitleTrackId = -1
                                showSubtitleDialog = false
                            }
                        )
                    }
                    items(selectableSubtitles, key = { it.id }) { track ->
                        ModalOptionRow(
                            title = track.name,
                            subtitle = null,
                            selected = track.id == currentSubtitleTrackId,
                            onClick = {
                                userToggledSubtitles = true
                                preferredSubtitleTrackId = track.id
                                mediaPlayer.spuTrack = track.id
                                currentSubtitleTrackId = track.id
                                showSubtitleDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    sizeDp: Int = 46,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color(0xFF3B82F6)
                    isPrimary -> Color.White
                    else -> Color.White.copy(alpha = 0.14f)
                }
            )
            .border(
                width = if (focused) 2.5.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                focused -> Color.White
                isPrimary -> Color.Black
                else -> Color.White
            },
            modifier = Modifier.size((sizeDp * 0.5f).dp)
        )
    }
}

@Composable
private fun PlayerPillButton(
    label: String,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                when {
                    focused -> Color(0xFF3B82F6)
                    isPrimary -> Color(0xFF1D4ED8).copy(alpha = 0.7f)
                    else -> Color.White.copy(alpha = 0.14f)
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(22.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TrackOrEpisodeModal(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40L)
        runCatching { closeFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF141621))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                PlayerIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onDismiss
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

@Composable
private fun ModalOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color(0xFF2563EB).copy(alpha = 0.45f)
                    selected -> Color(0xFF1D4ED8).copy(alpha = 0.28f)
                    else -> Color.White.copy(alpha = 0.06f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
        }
        if (selected) {
            Text("ACTIVE", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
