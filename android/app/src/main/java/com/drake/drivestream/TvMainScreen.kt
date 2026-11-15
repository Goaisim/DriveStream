package com.drake.drivestream

import android.app.Application
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

private val QUICK_TEST_LINKS = listOf(
    SavedLink(id = "quick-google", title = "Google", urlString = "https://www.google.com"),
    SavedLink(id = "quick-duckduckgo", title = "DuckDuckGo", urlString = "https://duckduckgo.com"),
    SavedLink(id = "quick-bing", title = "Bing", urlString = "https://www.bing.com")
)

private fun suggestTitleFromUrl(rawUrl: String): String {
    val host = SavedLinksManager.validHttpUri(rawUrl)?.host?.lowercase(Locale.US)
        ?.removePrefix("www.")
        .orEmpty()
    if (host.isEmpty()) return ""
    val primary = host.substringBefore('.')
    return primary.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
    }
}

// MARK: - Library Models & ViewModel

data class MediaItem(
    val file: DriveFile,
    val parsed: ParsedMediaInfo,
    val metadata: ShowMetadata? = null
) {
    val id: String get() = file.id
    val showTitle: String get() = metadata?.title ?: parsed.title
    val episodeCode: String? get() = parsed.episodeCode
    val episodeTitle: String? get() = metadata?.episodeTitle
    val overview: String? get() = metadata?.overview
    val stillUrl: String? get() = metadata?.stillUrl ?: file.thumbnailLink
    val posterUrl: String? get() = metadata?.posterUrl ?: stillUrl
}

data class ShowGroup(
    val id: String,
    val rawTitle: String,
    val title: String,
    val items: List<MediaItem>
) {
    val posterUrl: String? get() = items.firstNotNullOfOrNull { it.posterUrl }
    val seasons: List<Int> get() = items.mapNotNull { it.parsed.season }.distinct().sorted()
    val isSeries: Boolean get() = items.size > 1 || items.any { it.parsed.isEpisode }
}

data class ActiveTvPlayback(
    val episodes: List<PlayerEpisode>,
    val initialEpisode: PlayerEpisode
)

private data class CoverFinderTarget(
    val rawTitle: String,
    val displayTitle: String
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val authManager = AuthManager.getInstance(application)
    private val driveService = DriveService()
    private val metadataService = MetadataService.getInstance(application)

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var loadGeneration = 0

    fun buildShowGroups(sourceItems: List<MediaItem>): List<ShowGroup> {
        val validItems = sourceItems.filterNot { it.file.isFolder }
        val nonSeasonKeys = validItems
            .filterNot { metadataService.isSeasonOnlyTitle(it.showTitle) }
            .map { metadataService.normalizedShowKey(it.parsed.title) }
            .toSet()
        val soleShowKey = if (nonSeasonKeys.size == 1) nonSeasonKeys.first() else null

        val grouped = validItems.groupBy { item ->
            val key = metadataService.normalizedShowKey(item.parsed.title)
            if (metadataService.isSeasonOnlyTitle(item.parsed.title) && soleShowKey != null) {
                soleShowKey
            } else {
                key
            }
        }

        return grouped.mapNotNull { (key, groupItems) ->
            val sorted = groupItems.sortedWith(
                compareBy<MediaItem>(
                    { it.parsed.season ?: Int.MAX_VALUE },
                    { it.parsed.episode ?: Int.MAX_VALUE },
                    { it.file.name.lowercase(Locale.US) }
                )
            )
            val rawTitle = sorted.firstOrNull { !metadataService.isSeasonOnlyTitle(it.parsed.title) }?.parsed?.title
                ?: sorted.firstOrNull()?.parsed?.title
                ?: "Videos"
            val displayTitle = sorted.firstOrNull { !metadataService.isSeasonOnlyTitle(it.showTitle) }?.showTitle
                ?: rawTitle
            if (metadataService.isSeasonOnlyTitle(displayTitle)) return@mapNotNull null

            ShowGroup(
                id = key.ifEmpty { rawTitle },
                rawTitle = rawTitle,
                title = displayTitle,
                items = sorted
            )
        }.sortedBy { it.title.lowercase(Locale.US) }
    }

    fun loadLibrary(folderId: String) {
        viewModelScope.launch {
            loadGeneration += 1
            val generation = loadGeneration
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val files = driveService.fetchVideoFiles(folderId, authManager)
                if (generation != loadGeneration) return@launch

                val initial = files.map { file ->
                    MediaItem(
                        file = file,
                        parsed = metadataService.parseFilename(file.name, file.folderName),
                        metadata = null
                    )
                }
                _items.value = initial
                _isLoading.value = false

                if (initial.isNotEmpty()) {
                    enrichMetadata(initial, generation)
                }
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _isLoading.value = false
                _errorMessage.value = e.message ?: "Failed to scan Google Drive"
            }
        }
    }

    fun refreshEnrichedMetadata() {
        val current = _items.value
        if (current.isEmpty()) return
        viewModelScope.launch {
            loadGeneration += 1
            enrichMetadata(current, loadGeneration)
        }
    }

    private suspend fun enrichMetadata(sourceItems: List<MediaItem>, generation: Int) {
        val updated = sourceItems.toMutableList()
        val batchSize = 6
        for (start in sourceItems.indices step batchSize) {
            if (generation != loadGeneration) return
            val end = minOf(start + batchSize, sourceItems.size)
            coroutineScope {
                val jobs = (start until end).map { idx ->
                    async {
                        idx to metadataService.metadata(sourceItems[idx].file)
                    }
                }
                jobs.awaitAll().forEach { (idx, meta) ->
                    updated[idx] = updated[idx].copy(metadata = meta)
                }
            }
            if (generation != loadGeneration) return
            _items.value = updated.toList()
        }
    }

    fun makePlayback(item: MediaItem, queueSource: List<MediaItem>): ActiveTvPlayback {
        val episodes = queueSource.map(::toPlayerEpisode)
        val initial = episodes.firstOrNull { it.id == item.file.effectiveId } ?: toPlayerEpisode(item)
        return ActiveTvPlayback(episodes = episodes, initialEpisode = initial)
    }

    private fun toPlayerEpisode(item: MediaItem): PlayerEpisode {
        val code = item.episodeCode
        val epTitle = item.episodeTitle
        val displayTitle = when {
            code != null && !epTitle.isNullOrBlank() && epTitle != code -> "${item.showTitle} • $code $epTitle"
            code != null -> "${item.showTitle} • $code"
            else -> item.showTitle
        }
        val shortTitle = when {
            code != null && !epTitle.isNullOrBlank() && epTitle != code -> "$code • $epTitle"
            code != null -> code
            else -> item.file.name
        }
        return PlayerEpisode(
            id = item.file.effectiveId,
            title = displayTitle,
            shortTitle = shortTitle,
            subtitle = item.file.formattedSize,
            episodeCode = code,
            stillUrl = item.stillUrl
        )
    }
}

// MARK: - Root Screen

@Composable
fun TvMainScreen(libraryViewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val authManager = remember(context) { AuthManager.getInstance(context) }
    val linksManager = remember(context) { SavedLinksManager.getInstance(context) }
    val driveSettings = remember(context) { DriveSettings.getInstance(context) }
    val playbackStore = remember(context) { PlaybackStore.getInstance(context) }
    val metadataService = remember(context) { MetadataService.getInstance(context) }
    val syncManager = remember(context) { SyncManager.getInstance(context) }

    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    val hasAppDataScope by authManager.hasAppDataScope.collectAsState()
    val companionUrl by authManager.companionUrl.collectAsState()
    val savedLinks by linksManager.links.collectAsState()
    val folderId by driveSettings.folderId.collectAsState()
    val coversRevision by syncManager.coversRevision.collectAsState()

    var bypassSignIn by remember { mutableStateOf(false) }
    var activeWebLink by remember { mutableStateOf<SavedLink?>(null) }
    var activePlayback by remember { mutableStateOf<ActiveTvPlayback?>(null) }
    var selectedShowId by remember { mutableStateOf<String?>(null) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf<SavedLink?>(null) }
    var actionMenuLink by remember { mutableStateOf<SavedLink?>(null) }
    var coverFinderTarget by remember { mutableStateOf<CoverFinderTarget?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isAuthenticated, folderId) {
        if (isAuthenticated) {
            bypassSignIn = false
            syncManager.triggerManualSync()
            libraryViewModel.loadLibrary(folderId)
        }
    }

    LaunchedEffect(coversRevision) {
        if (coversRevision > 0 && isAuthenticated) {
            libraryViewModel.refreshEnrichedMetadata()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        when {
            activePlayback != null -> {
                val playback = activePlayback!!
                VlcPlayerScreen(
                    episodes = playback.episodes,
                    initialEpisode = playback.initialEpisode,
                    onClose = { activePlayback = null }
                )
            }

            activeWebLink != null -> {
                val link = activeWebLink!!
                AdBlockWebViewScreen(
                    initialUrl = link.urlString,
                    onClose = { activeWebLink = null }
                )
            }

            !isAuthenticated && !bypassSignIn -> {
                TvSignInScreen(
                    authManager = authManager,
                    companionUrl = companionUrl,
                    onContinueLinksOnly = { bypassSignIn = true }
                )
            }

            else -> {
                val items by libraryViewModel.items.collectAsState()
                val isLoading by libraryViewModel.isLoading.collectAsState()
                val errorMessage by libraryViewModel.errorMessage.collectAsState()
                val progressMap by playbackStore.progressByFileId.collectAsState()

                val showGroups = remember(items) { libraryViewModel.buildShowGroups(items) }
                val selectedGroup = selectedShowId?.let { id -> showGroups.firstOrNull { it.id == id } }

                if (selectedGroup != null) {
                    BackHandler { selectedShowId = null }
                    TvShowDetailScreen(
                        group = selectedGroup,
                        progressMap = progressMap,
                        onBack = { selectedShowId = null },
                        onPlay = { item ->
                            activePlayback = libraryViewModel.makePlayback(item, selectedGroup.items)
                        },
                        onOpenCoverFinder = {
                            coverFinderTarget = CoverFinderTarget(selectedGroup.rawTitle, selectedGroup.title)
                        }
                    )
                } else {
                    TvHomeContent(
                        isAuthenticated = isAuthenticated,
                        companionUrl = companionUrl,
                        savedLinks = savedLinks,
                        items = items,
                        showGroups = showGroups,
                        progressMap = progressMap,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onAddLink = {
                            editingLink = null
                            showAddEditDialog = true
                        },
                        onOpenLink = { link -> activeWebLink = link },
                        onLongPressLink = { link -> actionMenuLink = link },
                        onPlayItem = { item, queue ->
                            activePlayback = libraryViewModel.makePlayback(item, queue)
                        },
                        onRemoveContinueWatching = { item ->
                            playbackStore.clearProgress(item.file.effectiveId)
                        },
                        onOpenShow = { group -> selectedShowId = group.id },
                        onOpenCoverFinder = { group ->
                            coverFinderTarget = CoverFinderTarget(group.rawTitle, group.title)
                        },
                        onRefresh = {
                            authManager.refreshLocalIp()
                            if (isAuthenticated) libraryViewModel.loadLibrary(folderId)
                        },
                        onSignIn = { authManager.launchTvBrowserSignIn(context) },
                        onOpenSettings = {
                            authManager.refreshLocalIp()
                            showSettingsDialog = true
                        }
                    )
                }
            }
        }

        // Modals
        if (showAddEditDialog) {
            AddEditLinkTvDialog(
                existingLink = editingLink,
                onDismiss = {
                    showAddEditDialog = false
                    editingLink = null
                },
                onSave = { title, url ->
                    val existing = editingLink
                    val saved = if (existing == null) {
                        linksManager.addLink(title, url)
                    } else {
                        linksManager.updateLink(existing.id, title, url)
                    }
                    if (saved != null) {
                        syncManager.scheduleSyncAfterChange()
                    }
                    showAddEditDialog = false
                    editingLink = null
                },
                onSaveAndOpen = { title, url ->
                    val existing = editingLink
                    val saved = if (existing == null) {
                        linksManager.addLink(title, url)
                    } else {
                        linksManager.updateLink(existing.id, title, url)
                    }
                    showAddEditDialog = false
                    editingLink = null
                    if (saved != null) {
                        syncManager.scheduleSyncAfterChange()
                        activeWebLink = saved
                    }
                },
                onQuickOpen = { quickLink ->
                    showAddEditDialog = false
                    editingLink = null
                    activeWebLink = quickLink
                }
            )
        }

        actionMenuLink?.let { targetLink ->
            LinkActionsTvDialog(
                link = targetLink,
                onDismiss = { actionMenuLink = null },
                onOpen = {
                    actionMenuLink = null
                    activeWebLink = targetLink
                },
                onEdit = {
                    actionMenuLink = null
                    editingLink = targetLink
                    showAddEditDialog = true
                },
                onDelete = {
                    actionMenuLink = null
                    linksManager.deleteLink(targetLink.id)
                    syncManager.scheduleSyncAfterChange()
                }
            )
        }

        coverFinderTarget?.let { target ->
            CoverFinderTvDialog(
                rawShowTitle = target.rawTitle,
                initialQuery = target.displayTitle,
                metadataService = metadataService,
                onDismiss = { coverFinderTarget = null },
                onApplied = {
                    coverFinderTarget = null
                    syncManager.scheduleSyncAfterChange()
                    libraryViewModel.refreshEnrichedMetadata()
                }
            )
        }

        if (showSettingsDialog) {
            TvSettingsDialog(
                isAuthenticated = isAuthenticated,
                hasAppDataScope = hasAppDataScope,
                companionUrl = companionUrl,
                currentFolderId = folderId,
                syncManager = syncManager,
                onDismiss = { showSettingsDialog = false },
                onSaveFolderId = { newId ->
                    val changed = newId.trim() != folderId
                    driveSettings.setFolderId(newId)
                    showSettingsDialog = false
                    if (changed && isAuthenticated) {
                        libraryViewModel.loadLibrary(newId.trim())
                    }
                },
                onEnableCloudSync = {
                    showSettingsDialog = false
                    authManager.launchTvBrowserSignIn(context)
                },
                onClearMetadataCache = {
                    metadataService.clearCache()
                    if (isAuthenticated) {
                        libraryViewModel.refreshEnrichedMetadata()
                    }
                },
                onSignOutOrSignIn = {
                    showSettingsDialog = false
                    if (isAuthenticated) {
                        authManager.signOut()
                    } else {
                        bypassSignIn = false
                    }
                }
            )
        }
    }
}

// MARK: - Sign In Screen

@Composable
private fun TvSignInScreen(
    authManager: AuthManager,
    companionUrl: String,
    onContinueLinksOnly: () -> Unit
) {
    val context = LocalContext.current
    val isSigningIn by authManager.isSigningIn.collectAsState()
    val authError by authManager.authError.collectAsState()
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { primaryFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF141827), Color(0xFF0A0A0F))
                )
            )
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(0.9f),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF2563EB).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(42.dp)
                    )
                }

                Text(
                    text = "DriveStream TV",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Stream MKV, AVI, and MP4 shows from Google Drive with LibVLC 3.6.0, TVMaze covers, and an ad-blocked Web Browser.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )

                if (!authError.isNullOrBlank()) {
                    Text(
                        text = authError.orEmpty(),
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        label = if (isSigningIn) "Signing In…" else "Sign in on TV Browser",
                        icon = Icons.Default.PlayArrow,
                        isPrimary = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                        onClick = { authManager.launchTvBrowserSignIn(context) }
                    )

                    TvActionButton(
                        label = "Use Web Links Only",
                        icon = Icons.Default.Public,
                        onClick = onContinueLinksOnly
                    )
                }
            }

            // Companion Server Card
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF141621))
                    .border(1.dp, Color(0xFF2B324D), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Sign In or Add Links from Phone / PC",
                    color = Color(0xFF93C5FD),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Open this URL in Safari or any browser on the same Wi-Fi network to complete Google OAuth PKCE or manage Web Links without typing on the TV:",
                    color = Color(0xFFD1D5DB),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0B12))
                        .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(vertical = 14.dp, horizontal = 16.dp)
                ) {
                    Text(
                        text = companionUrl,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Listening on 0.0.0.0:${AuthManager.COMPANION_PORT}",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// MARK: - Home Screen

@Composable
private fun TvHomeContent(
    isAuthenticated: Boolean,
    companionUrl: String,
    savedLinks: List<SavedLink>,
    items: List<MediaItem>,
    showGroups: List<ShowGroup>,
    progressMap: Map<String, PlaybackProgress>,
    isLoading: Boolean,
    errorMessage: String?,
    onAddLink: () -> Unit,
    onOpenLink: (SavedLink) -> Unit,
    onLongPressLink: (SavedLink) -> Unit,
    onPlayItem: (MediaItem, List<MediaItem>) -> Unit,
    onRemoveContinueWatching: (MediaItem) -> Unit,
    onOpenShow: (ShowGroup) -> Unit,
    onOpenCoverFinder: (ShowGroup) -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val continueWatching = remember(items, progressMap) {
        items.mapNotNull { item ->
            val p = progressMap[item.file.effectiveId]
            if (p != null && p.isInProgress) item to p else null
        }.sortedByDescending { it.second.updatedAtMs }
    }

    val topAddFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { topAddFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10121B))
                .padding(horizontal = 36.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DriveStream",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Companion: $companionUrl",
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.weight(1f))

            TvActionButton(
                label = "Add Link",
                icon = Icons.Default.AddCircle,
                isPrimary = true,
                modifier = Modifier.focusRequester(topAddFocus),
                onClick = onAddLink
            )

            Spacer(Modifier.width(10.dp))

            TvIconCircleButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh",
                onClick = onRefresh
            )

            Spacer(Modifier.width(10.dp))

            TvIconCircleButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    item(key = "error") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF7F1D1D).copy(alpha = 0.55f))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFACC15))
                            Text(errorMessage, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                // Web Links + Quick Test Browser Row (always visible so Ad-Block WebView can be tested in 1 click)
                item(key = "web-links-header") {
                    Text(
                        text = if (savedLinks.isNotEmpty()) {
                            "Web Links  (Hold OK / Menu for Edit & Delete)"
                        } else {
                            "Web Links & Quick Test Browser"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 36.dp)
                    )
                }
                item(key = "web-links-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(savedLinks, key = { it.id }) { link ->
                            SavedLinkTvCard(
                                link = link,
                                onClick = { onOpenLink(link) },
                                onLongClick = { onLongPressLink(link) }
                            )
                        }
                        items(QUICK_TEST_LINKS, key = { it.id }) { quick ->
                            SavedLinkTvCard(
                                link = quick,
                                subtitleText = "Quick Test • ${quick.displayDomain}",
                                onClick = { onOpenLink(quick) },
                                onLongClick = null
                            )
                        }
                    }
                }

                // Continue Watching Row
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue-header") {
                        Text(
                            text = "Continue Watching",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 36.dp)
                        )
                    }
                    item(key = "continue-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(continueWatching, key = { it.first.id }) { (item, prog) ->
                                ContinueWatchingTvCard(
                                    item = item,
                                    progress = prog,
                                    onClick = { onPlayItem(item, listOf(item)) },
                                    onLongClick = { onRemoveContinueWatching(item) }
                                )
                            }
                        }
                    }
                }

                // Google Drive Library Section (always visible below Web Links)
                item(key = "library-header") {
                    Text(
                        text = if (showGroups.isNotEmpty()) {
                            "Google Drive Library  (Hold OK / Menu to Find Cover)"
                        } else {
                            "Google Drive Library"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 36.dp)
                    )
                }

                when {
                    !isAuthenticated -> {
                        item(key = "library-sign-in-hint") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF141621))
                                    .border(1.dp, Color(0xFF2B324D), RoundedCornerShape(14.dp))
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(36.dp))
                                Text(
                                    text = "Google Drive Not Connected",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Sign in to load your Google Drive Shows & Movies below Web Links, or open $companionUrl from your phone or PC.",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                TvActionButton(
                                    label = "Sign in on TV Browser",
                                    icon = Icons.Default.PlayArrow,
                                    isPrimary = true,
                                    onClick = onSignIn
                                )
                            }
                        }
                    }

                    isLoading && items.isEmpty() -> {
                        item(key = "library-loading-inline") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF141621))
                                    .padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF3B82F6),
                                    modifier = Modifier.size(26.dp)
                                )
                                Text(
                                    text = "Scanning Google Drive…",
                                    color = Color(0xFFD1D5DB),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    showGroups.isNotEmpty() -> {
                        val chunked = showGroups.chunked(5)
                        items(chunked.size, key = { idx -> "row-$idx" }) { rowIdx ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 36.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                val rowGroups = chunked[rowIdx]
                                rowGroups.forEach { group ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        ShowTvCard(
                                            group = group,
                                            onClick = {
                                                if (group.isSeries) {
                                                    onOpenShow(group)
                                                } else {
                                                    group.items.firstOrNull()?.let { single ->
                                                        onPlayItem(single, group.items)
                                                    }
                                                }
                                            },
                                            onLongClick = { onOpenCoverFinder(group) }
                                        )
                                    }
                                }
                                repeat(5 - rowGroups.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    errorMessage == null -> {
                        item(key = "empty-library-hint") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF141621))
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF6B7280), modifier = Modifier.size(40.dp))
                                Text(
                                    text = "No Google Drive Videos Yet",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Upload videos to Google Drive (or check folder ID in Settings) and tap Refresh.",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                TvActionButton(
                                    label = "Refresh Drive Library",
                                    icon = Icons.Default.Refresh,
                                    onClick = onRefresh
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Cards with High-Contrast D-Pad Focus Rings

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedLinkTvCard(
    link: SavedLink,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    subtitleText: String = link.displayDomain
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(230.dp)
            .scale(if (focused) 1.04f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1D243B) else Color(0xFF141621))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (onLongClick != null && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                    onLongClick()
                    true
                } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.size(44.dp)) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2563EB).copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2563EB))
                )
            }

            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Ad-Blocked",
                tint = Color(0xFF4ADE80),
                modifier = Modifier.size(16.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = link.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitleText,
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingTvCard(
    item: MediaItem,
    progress: PlaybackProgress,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(240.dp)
            .scale(if (focused) 1.04f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141621))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(135.dp)
                .background(Color.White.copy(alpha = 0.06f))
        ) {
            if (!item.stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.stillUrl,
                    contentDescription = item.showTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item.episodeCode?.let { code ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(code, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF3B82F6),
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                text = item.showTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = item.episodeTitle?.takeIf { it.isNotBlank() && it != item.episodeCode }
                ?: formatRemainingText(progress)
            Text(
                text = sub,
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowTvCard(
    group: ShowGroup,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.04f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                    onLongClick()
                    true
                } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            if (!group.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = group.posterUrl,
                    contentDescription = group.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = if (group.isSeries) Icons.Default.Tv else Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            }

            if (group.isSeries) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${group.items.size} ep",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = group.title,
            color = if (focused) Color(0xFF93C5FD) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// MARK: - Show Detail Screen

@Composable
private fun TvShowDetailScreen(
    group: ShowGroup,
    progressMap: Map<String, PlaybackProgress>,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenCoverFinder: () -> Unit
) {
    var selectedSeason by remember(group.id) { mutableStateOf(group.seasons.firstOrNull()) }
    val filteredItems = remember(group, selectedSeason) {
        val season = selectedSeason
        if (season == null || group.seasons.isEmpty()) group.items
        else group.items.filter { it.parsed.season == season }
    }

    val backFocus = remember { FocusRequester() }
    LaunchedEffect(group.id) {
        delay(50L)
        runCatching { backFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10121B))
                .padding(horizontal = 36.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvIconCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.focusRequester(backFocus),
                onClick = onBack
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = group.title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TvActionButton(
                label = "Find Cover",
                icon = Icons.Default.AutoAwesome,
                onClick = onOpenCoverFinder
            )
        }

        if (group.seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(group.seasons, key = { it }) { season ->
                    val isSelected = selectedSeason == season
                    TvActionButton(
                        label = "Season $season",
                        isPrimary = isSelected,
                        onClick = { selectedSeason = season }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredItems, key = { it.id }) { item ->
                val progress = progressMap[item.file.effectiveId]
                EpisodeTvRow(
                    item = item,
                    progress = progress,
                    onClick = { onPlay(item) }
                )
            }
        }
    }
}

@Composable
private fun EpisodeTvRow(
    item: MediaItem,
    progress: PlaybackProgress?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF1E2640) else Color(0xFF141621))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(176.dp)
                .height(99.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            if (!item.stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.stillUrl,
                    contentDescription = item.episodeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                )
            }

            if (progress != null && progress.isInProgress) {
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = Color.Black.copy(alpha = 0.5f)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item.episodeCode?.let { code ->
                    Text(code, color = Color(0xFF60A5FA), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                item.file.formattedSize?.let { size ->
                    Text(size, color = Color(0xFF9CA3AF), fontSize = 12.sp)
                }
            }
            val title = item.episodeTitle?.takeIf { it.isNotBlank() && it != item.episodeCode }
                ?: item.file.name
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.overview.isNullOrBlank()) {
                Text(
                    text = item.overview.orEmpty(),
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// MARK: - Dialogs

@Composable
private fun AddEditLinkTvDialog(
    existingLink: SavedLink?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onSaveAndOpen: (String, String) -> Unit,
    onQuickOpen: (SavedLink) -> Unit
) {
    var url by remember(existingLink) { mutableStateOf(existingLink?.urlString ?: "") }
    var title by remember(existingLink) { mutableStateOf(existingLink?.title ?: "") }
    var editingField by remember { mutableStateOf<String?>(null) }
    var focusSaveAndOpenPending by remember { mutableStateOf(false) }

    val urlButtonFocus = remember { FocusRequester() }
    val saveAndOpenFocus = remember { FocusRequester() }
    val validUri = SavedLinksManager.validHttpUri(url)
    val autoTitle = suggestTitleFromUrl(url)

    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { urlButtonFocus.requestFocus() }
    }

    LaunchedEffect(editingField, focusSaveAndOpenPending) {
        if (editingField == null && focusSaveAndOpenPending) {
            focusSaveAndOpenPending = false
            delay(60L)
            runCatching { saveAndOpenFocus.requestFocus() }
        }
    }

    BackHandler {
        if (editingField != null) {
            editingField = null
        } else {
            onDismiss()
        }
    }

    TvDialogSurface(
        title = if (existingLink == null) "Add Web Link" else "Edit Web Link",
        onDismiss = onDismiss,
        maxWidthFraction = 0.62f
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvDialogFieldButton(
                label = "Enter Website URL",
                value = if (url.isBlank()) "Press OK to type a domain (e.g. google.com)" else url,
                isPlaceholder = url.isBlank(),
                modifier = Modifier.focusRequester(urlButtonFocus),
                onClick = { editingField = "url" }
            )

            TvDialogFieldButton(
                label = "Website Title (Optional)",
                value = when {
                    title.isNotBlank() -> title
                    autoTitle.isNotEmpty() -> "Auto: $autoTitle"
                    else -> "Press OK to enter custom title"
                },
                isPlaceholder = title.isBlank(),
                onClick = { editingField = "title" }
            )

            Text(
                text = if (validUri != null) {
                    "Saves as $validUri"
                } else {
                    "Select Enter Website URL above, or launch a Quick Test Browser site below."
                },
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = "Save & Open in Ad-Block Browser",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    enabled = validUri != null,
                    modifier = Modifier.focusRequester(saveAndOpenFocus),
                    onClick = { onSaveAndOpen(title.ifBlank { autoTitle }, url) }
                )
                TvActionButton(
                    label = "Save",
                    enabled = validUri != null,
                    onClick = { onSave(title.ifBlank { autoTitle }, url) }
                )
                TvActionButton(
                    label = "Cancel",
                    onClick = onDismiss
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "Quick Test Browser (No Typing):",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QUICK_TEST_LINKS.forEach { quick ->
                    TvActionButton(
                        label = quick.title,
                        icon = Icons.Default.Public,
                        onClick = { onQuickOpen(quick) }
                    )
                }
            }
        }
    }

    when (editingField) {
        "url" -> SingleFieldTvInputDialog(
            title = "Enter Website URL",
            label = "Website URL or Domain (https:// added automatically)",
            initialValue = url,
            keyboardType = KeyboardType.Uri,
            onDismiss = { editingField = null },
            onConfirm = { entered ->
                val trimmed = entered.trim()
                url = trimmed
                if (title.isBlank()) {
                    val suggested = suggestTitleFromUrl(trimmed)
                    if (suggested.isNotEmpty()) {
                        title = suggested
                    }
                }
                editingField = null
                if (SavedLinksManager.validHttpUri(trimmed) != null) {
                    focusSaveAndOpenPending = true
                }
            }
        )

        "title" -> SingleFieldTvInputDialog(
            title = "Website Title (Optional)",
            label = "Display Title",
            initialValue = title,
            keyboardType = KeyboardType.Text,
            onDismiss = { editingField = null },
            onConfirm = { entered ->
                title = entered.trim()
                editingField = null
            }
        )
    }
}

@Composable
private fun TvDialogFieldButton(
    label: String,
    value: String,
    isPlaceholder: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0xFF1D243B) else Color(0xFF0A0B12))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = value,
            color = if (isPlaceholder) Color(0xFF6B7280) else Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SingleFieldTvInputDialog(
    title: String,
    label: String,
    initialValue: String,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    var draft by remember(initialValue) { mutableStateOf(initialValue) }

    fun finishSubmit() {
        keyboardController?.hide()
        focusManager.clearFocus()
        onConfirm(draft)
    }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching {
            inputFocus.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler {
        keyboardController?.hide()
        onDismiss()
    }

    TvDialogSurface(
        title = title,
        onDismiss = {
            keyboardController?.hide()
            onDismiss()
        },
        maxWidthFraction = 0.56f
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TvTextInput(
                label = label,
                value = draft,
                onValueChange = { draft = it },
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
                inputFocusRequester = inputFocus,
                onSubmit = { finishSubmit() }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = "Done",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    onClick = { finishSubmit() }
                )
                TvActionButton(
                    label = "Cancel",
                    onClick = {
                        keyboardController?.hide()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun LinkActionsTvDialog(
    link: SavedLink,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50L)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }

    TvDialogSurface(
        title = link.title,
        subtitle = link.urlString,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(
                label = "Open in Ad-Blocked Browser",
                icon = Icons.Default.PlayCircle,
                isPrimary = true,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                onClick = onOpen
            )
            TvActionButton(
                label = "Edit Link",
                icon = Icons.Default.Edit,
                modifier = Modifier.fillMaxWidth(),
                onClick = onEdit
            )
            TvActionButton(
                label = "Delete Link",
                icon = Icons.Default.Delete,
                isDanger = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun CoverFinderTvDialog(
    rawShowTitle: String,
    initialQuery: String,
    metadataService: MetadataService,
    onDismiss: () -> Unit,
    onApplied: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<CoverSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    fun runSearch() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            isSearching = true
            results = metadataService.searchShowCandidates(trimmed)
            isSearching = false
        }
    }

    LaunchedEffect(Unit) {
        runSearch()
    }
    BackHandler { onDismiss() }

    TvDialogSurface(
        title = "Find Cover / Match",
        onDismiss = onDismiss,
        maxWidthFraction = 0.68f,
        maxHeightFraction = 0.82f
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    TvTextInput(
                        label = "Search show or movie title…",
                        value = query,
                        onValueChange = { query = it },
                        onSubmit = { runSearch() }
                    )
                }
                TvActionButton(
                    label = "Search",
                    icon = Icons.Default.Search,
                    isPrimary = true,
                    onClick = { runSearch() }
                )
            }

            if (isSearching) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF3B82F6))
                }
            } else if (results.isEmpty()) {
                Text("No matches found. Try a shorter show or movie title.", color = Color(0xFF9CA3AF), fontSize = 14.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.id }) { result ->
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Color(0xFF1E2640) else Color.White.copy(alpha = 0.06f))
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .onFocusChanged { focused = it.isFocused }
                                .focusable()
                                .clickable {
                                    scope.launch {
                                        metadataService.applyCoverSelection(result, rawShowTitle)
                                        onApplied()
                                    }
                                }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                if (!result.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = result.posterUrl,
                                        contentDescription = result.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(result.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = result.kindLabel,
                                        color = if (result.isMovie) Color(0xFFFDBA74) else Color(0xFFC4B5FD),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(result.source.label, color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                result.summary?.let {
                                    Text(it, color = Color(0xFF9CA3AF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSettingsDialog(
    isAuthenticated: Boolean,
    hasAppDataScope: Boolean,
    companionUrl: String,
    currentFolderId: String,
    syncManager: SyncManager,
    onDismiss: () -> Unit,
    onSaveFolderId: (String) -> Unit,
    onEnableCloudSync: () -> Unit,
    onClearMetadataCache: () -> Unit,
    onSignOutOrSignIn: () -> Unit
) {
    val savedPeerHost by syncManager.peerHost.collectAsState()
    val isSyncing by syncManager.isSyncing.collectAsState()
    val isScanning by syncManager.isScanning.collectAsState()
    val syncStatus by syncManager.statusMessage.collectAsState()

    var draftFolderId by remember(currentFolderId) { mutableStateOf(currentFolderId) }
    var draftPeerHost by remember(savedPeerHost) { mutableStateOf(savedPeerHost) }
    var cacheCleared by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    TvDialogSurface(
        title = "Settings",
        maxHeightFraction = 0.88f,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Companion & LAN Peer Server: $companionUrl",
                color = Color(0xFF93C5FD),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Sync Status: $syncStatus" + when {
                    isScanning -> " (scanning…)"
                    isSyncing -> " (syncing…)"
                    hasAppDataScope -> " • Drive Cloud Enabled"
                    isAuthenticated -> " • Drive Cloud Re-Consent Needed"
                    else -> ""
                },
                color = if (hasAppDataScope) Color(0xFF4ADE80) else Color(0xFFD1D5DB),
                fontSize = 12.sp
            )

            if (isAuthenticated && !hasAppDataScope) {
                TvActionButton(
                    label = "Enable Cloud Sync (Google Drive)",
                    isPrimary = true,
                    onClick = onEnableCloudSync
                )
            }

            TvTextInput(
                label = "Optional Peer TV LAN IP (iOS auto-discovers this TV on :8766)",
                value = draftPeerHost,
                onValueChange = { draftPeerHost = it },
                onSubmit = {
                    syncManager.setPeerHost(draftPeerHost)
                    syncManager.triggerManualSync()
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    label = if (isSyncing) "Syncing…" else "Sync Now",
                    isPrimary = true,
                    enabled = !isSyncing && !isScanning,
                    onClick = {
                        syncManager.setPeerHost(draftPeerHost)
                        syncManager.triggerManualSync()
                    }
                )
                TvActionButton(
                    label = if (isScanning) "Scanning…" else "Scan Wi-Fi",
                    enabled = !isScanning,
                    onClick = {
                        syncManager.scanLocalNetworkForPeer()
                    }
                )
            }

            TvTextInput(
                label = "Google Drive Folder ID (blank = all videos)",
                value = draftFolderId,
                onValueChange = { draftFolderId = it }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    label = "Save Folder ID",
                    isPrimary = true,
                    onClick = {
                        syncManager.setPeerHost(draftPeerHost)
                        onSaveFolderId(draftFolderId)
                    }
                )
                TvActionButton(
                    label = if (cacheCleared) "Cache Cleared" else "Clear Show Cache",
                    onClick = {
                        onClearMetadataCache()
                        cacheCleared = true
                    }
                )
            }

            TvActionButton(
                label = if (isAuthenticated) "Sign Out of Google" else "Sign In with Google",
                isDanger = isAuthenticated,
                onClick = onSignOutOrSignIn
            )
        }
    }
}

// MARK: - Shared TV Components

@Composable
private fun TvDialogSurface(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    maxWidthFraction: Float = 0.56f,
    maxHeightFraction: Float = 0.78f,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.76f))
            .padding(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(maxWidthFraction)
                .fillMaxHeight(maxHeightFraction)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF141621))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = Color(0xFF9CA3AF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TvIconCircleButton(icon = Icons.Default.Close, contentDescription = "Close", onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
private fun TvTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done,
    inputFocusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFF9CA3AF), fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0A0B12))
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            val baseModifier = Modifier
                .fillMaxWidth()
                .let { mod -> if (inputFocusRequester != null) mod.focusRequester(inputFocusRequester) else mod }
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (
                        onSubmit != null &&
                        ev.type == KeyEventType.KeyDown &&
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color(0xFF3B82F6)),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmit?.invoke() },
                    onDone = { onSubmit?.invoke() },
                    onNext = { onSubmit?.invoke() }
                ),
                modifier = baseModifier
            )
        }
    }
}

@Composable
private fun TvActionButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.06f)
                    isDanger && focused -> Color(0xFFDC2626)
                    isDanger -> Color(0xFF991B1B)
                    focused -> Color(0xFF3B82F6)
                    isPrimary -> Color(0xFF2563EB)
                    else -> Color.White.copy(alpha = 0.1f)
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TvIconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.1f))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun formatRemainingText(progress: PlaybackProgress): String {
    if (progress.duration <= 0.0) {
        return "Resume ${formatShortSec(progress.position)}"
    }
    val remaining = (progress.duration - progress.position).coerceAtLeast(0.0)
    val mins = (remaining / 60.0).toInt()
    return if (mins > 0) "${mins}m left" else "<1m left"
}

private fun formatShortSec(seconds: Double): String {
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
