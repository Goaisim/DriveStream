package com.drake.drivestream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class SoulseekHubTab { SEARCH, TRANSFERS }
private enum class SoulseekSearchMode { FOLDERS, FILES }

@Composable
fun SoulseekHubOverlay(
    targetFolderId: String,
    onLibraryItemAdded: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { AuthManager.getInstance(context) }
    val syncManager = remember { SyncManager.getInstance(context) }
    val soulseek = remember { SoulseekService.getInstance(context) }

    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    val hasDriveWriteScope by authManager.hasDriveWriteScope.collectAsState()
    val syncedBridgeHost by syncManager.soulseekBridgeHost.collectAsState()

    val bridgeReachable by soulseek.bridgeReachable.collectAsState()
    val isScanning by soulseek.isScanning.collectAsState()
    val statusMessage by soulseek.statusMessage.collectAsState()
    val isSearching by soulseek.isSearching.collectAsState()
    val searchError by soulseek.searchError.collectAsState()
    val folders by soulseek.folders.collectAsState()
    val files by soulseek.files.collectAsState()
    val transfers by soulseek.transfers.collectAsState()
    val activeEnqueueId by soulseek.activeEnqueueId.collectAsState()
    val actionMessage by soulseek.actionMessage.collectAsState()

    var activeTab by remember { mutableStateOf(SoulseekHubTab.SEARCH) }
    var searchMode by remember { mutableStateOf(SoulseekSearchMode.FOLDERS) }
    var searchQuery by remember { mutableStateOf("") }
    var editingSearch by remember { mutableStateOf(false) }
    var editingBridgeIp by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        soulseek.onUploadCompleted = onLibraryItemAdded
        soulseek.ensureConnectedAndRefresh()
        soulseek.startPollingTransfers()
        runCatching { initialFocus.requestFocus() }
    }

    DisposableEffect(Unit) {
        onDispose {
            soulseek.stopPollingTransfers()
        }
    }

    BackHandler {
        when {
            editingSearch -> editingSearch = false
            editingBridgeIp -> editingBridgeIp = false
            else -> onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B12))
            .padding(horizontal = 32.dp, vertical = 22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Soulseek Media Hub",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (bridgeReachable) Color(0xFF10B981).copy(alpha = 0.18f)
                            else Color(0xFFF59E0B).copy(alpha = 0.18f)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (bridgeReachable) Color(0xFF34D399) else Color(0xFFFBBF24))
                    )
                    Text(
                        text = statusMessage,
                        color = if (bridgeReachable) Color(0xFF6EE7B7) else Color(0xFFFDE68A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.weight(1f))

                TvActionButton(
                    label = "Search",
                    icon = Icons.Default.Search,
                    isPrimary = activeTab == SoulseekHubTab.SEARCH,
                    onClick = { activeTab = SoulseekHubTab.SEARCH }
                )

                TvActionButton(
                    label = "Transfers (${transfers.size})",
                    icon = Icons.Default.CloudDownload,
                    isPrimary = activeTab == SoulseekHubTab.TRANSFERS,
                    onClick = { activeTab = SoulseekHubTab.TRANSFERS }
                )

                TvIconCircleButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Refresh Transfers",
                    onClick = { scope.launch { soulseek.refreshTransfers() } }
                )

                TvIconCircleButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close Soulseek Hub",
                    onClick = onClose
                )
            }

            // PC Bridge controls row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvDialogFieldButton(
                    label = "PC Bridge IP (Port ${SoulseekService.BRIDGE_PORT})",
                    value = syncedBridgeHost.ifEmpty { "Not set — select Scan Wi-Fi or enter IP" },
                    isPlaceholder = syncedBridgeHost.isEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = { editingBridgeIp = true }
                )

                TvActionButton(
                    label = if (isScanning) "Scanning…" else "Scan Wi-Fi",
                    icon = Icons.Default.Wifi,
                    enabled = !isScanning,
                    onClick = { soulseek.scanWiFiForBridge() }
                )
            }

            if (!isAuthenticated || !hasDriveWriteScope) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF92400E).copy(alpha = 0.35f))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google Drive upload permission required",
                            color = Color(0xFFFDE68A),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Push Google Drive login from iPhone DriveStream (${authManager.companionUrl.collectAsState().value}) or sign in via System Browser.",
                            color = Color(0xFFE5E7EB),
                            fontSize = 12.sp
                        )
                    }
                    TvActionButton(
                        label = "Sign In with System Browser",
                        isPrimary = true,
                        onClick = { authManager.launchTvBrowserSignIn(context) }
                    )
                }
            }

            actionMessage?.let { msg ->
                Text(
                    text = msg,
                    color = Color(0xFF6EE7B7),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (activeTab == SoulseekHubTab.SEARCH) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvDialogFieldButton(
                            label = "Search Soulseek (Movies, TV Shows, S01…)",
                            value = searchQuery.ifEmpty { "Tap OK to type search query…" },
                            isPlaceholder = searchQuery.isEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(initialFocus),
                            onClick = { editingSearch = true }
                        )

                        TvActionButton(
                            label = if (isSearching) "Searching…" else "Search",
                            icon = Icons.Default.Search,
                            isPrimary = true,
                            enabled = searchQuery.trim().isNotEmpty(),
                            onClick = { soulseek.startSearch(searchQuery) }
                        )

                        if (isSearching) {
                            CircularProgressIndicator(
                                color = Color(0xFF60A5FA),
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(
                            label = "Season / Folder Packs (${folders.size})",
                            icon = Icons.Default.Folder,
                            isPrimary = searchMode == SoulseekSearchMode.FOLDERS,
                            onClick = { searchMode = SoulseekSearchMode.FOLDERS }
                        )
                        TvActionButton(
                            label = "Individual Video Files (${files.size})",
                            icon = Icons.Default.Movie,
                            isPrimary = searchMode == SoulseekSearchMode.FILES,
                            onClick = { searchMode = SoulseekSearchMode.FILES }
                        )
                    }

                    searchError?.let { err ->
                        Text(err, color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    }

                    val downloadsBlocked = !isAuthenticated || !hasDriveWriteScope || activeEnqueueId != null

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (searchMode == SoulseekSearchMode.FOLDERS) {
                            if (folders.isEmpty() && !isSearching) {
                                item {
                                    EmptyHubHint(
                                        title = "Search for full TV seasons or movies",
                                        subtitle = "Folder packs batch every video in a peer directory into one click and organize uploads by Show/Season XX."
                                    )
                                }
                            } else {
                                items(folders, key = { it.id }) { pack ->
                                    FolderPackTvRow(
                                        pack = pack,
                                        isBusy = activeEnqueueId == pack.id,
                                        disabled = downloadsBlocked,
                                        onDownload = {
                                            soulseek.downloadFolder(
                                                pack = pack,
                                                targetFolderId = targetFolderId,
                                                onQueued = { activeTab = SoulseekHubTab.TRANSFERS }
                                            )
                                        }
                                    )
                                }
                            }
                        } else {
                            if (files.isEmpty() && !isSearching) {
                                item {
                                    EmptyHubHint(
                                        title = "No individual video files yet",
                                        subtitle = "Run a search to inspect .mkv, .mp4, .m4v, and .avi peers sorted by Free Slot and speed."
                                    )
                                }
                            } else {
                                items(files, key = { it.id }) { file ->
                                    FileResultTvRow(
                                        file = file,
                                        isBusy = activeEnqueueId == file.id,
                                        disabled = downloadsBlocked,
                                        onDownload = {
                                            soulseek.downloadFile(
                                                file = file,
                                                targetFolderId = targetFolderId,
                                                onQueued = { activeTab = SoulseekHubTab.TRANSFERS }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (transfers.isEmpty()) {
                        item {
                            EmptyHubHint(
                                title = "No active transfers",
                                subtitle = "Queued Soulseek downloads automatically upload to your Google Drive folder and clear from PC disk."
                            )
                        }
                    } else {
                        items(transfers, key = { it.id }) { item ->
                            TransferTvRow(item)
                        }
                    }
                }
            }
        }
    }

    if (editingSearch) {
        SingleFieldTvInputDialog(
            title = "Search Soulseek",
            label = "Movie or TV Show Query",
            initialValue = searchQuery,
            keyboardType = KeyboardType.Text,
            onDismiss = { editingSearch = false },
            onConfirm = { query ->
                searchQuery = query
                editingSearch = false
                if (query.trim().isNotEmpty()) {
                    soulseek.startSearch(query)
                }
            }
        )
    }

    if (editingBridgeIp) {
        SingleFieldTvInputDialog(
            title = "Soulseek PC Bridge IP",
            label = "PC LAN IP Address (port ${SoulseekService.BRIDGE_PORT})",
            initialValue = syncedBridgeHost,
            keyboardType = KeyboardType.Uri,
            onDismiss = { editingBridgeIp = false },
            onConfirm = { rawHost ->
                editingBridgeIp = false
                soulseek.setManualBridgeHost(rawHost)
            }
        )
    }
}

@Composable
private fun FolderPackTvRow(
    pack: SoulseekFolderPack,
    isBusy: Boolean,
    disabled: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = pack.folderDisplayName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                QualityBadge(pack.qualityTag)
                PeerSlotBadge(pack.hasFreeUploadSlot, pack.queueLength)
            }
            Text(
                text = pack.summary,
                color = Color(0xFFD1D5DB),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${pack.username} • ${SoulseekFormatting.formatSpeed(pack.uploadSpeed)}",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
        }

        TvActionButton(
            label = if (isBusy) "Queuing…" else "Download Entire Folder",
            icon = Icons.Default.CloudDownload,
            isPrimary = true,
            enabled = !disabled,
            onClick = onDownload
        )
    }
}

@Composable
private fun FileResultTvRow(
    file: SoulseekFileResult,
    isBusy: Boolean,
    disabled: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = file.basename,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                QualityBadge(file.qualityTag)
                PeerSlotBadge(file.hasFreeUploadSlot, file.queueLength)
            }
            Text(
                text = "${SoulseekFormatting.formatSize(file.size)} • ${file.username} • ${SoulseekFormatting.formatSpeed(file.uploadSpeed)}",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
        }

        TvActionButton(
            label = if (isBusy) "Queuing…" else "Download to Drive",
            icon = Icons.Default.CloudDownload,
            isPrimary = true,
            enabled = !disabled,
            onClick = onDownload
        )
    }
}

@Composable
private fun TransferTvRow(item: SoulseekTransferItem) {
    var focused by remember { mutableStateOf(false) }
    val progressColor = when (item.stage) {
        "completed" -> Color(0xFF10B981)
        "failed" -> Color(0xFFEF4444)
        "uploading", "queued_upload" -> Color(0xFF06B6D4)
        else -> Color(0xFF3B82F6)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.stage == "completed") {
                Text(
                    text = "Added to Library ✓",
                    color = Color(0xFF6EE7B7),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = { (item.percent.toFloat() / 100f).coerceIn(0f, 1f) },
            color = progressColor,
            trackColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.statusText,
                color = if (item.stage == "failed") Color(0xFFFCA5A5) else Color(0xFFD1D5DB),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${item.username} • ${SoulseekFormatting.formatSize(item.size)}",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
        }

        if (item.driveFolderPath.isNotEmpty()) {
            Text(
                text = "Drive: ${item.driveFolderPath}",
                color = Color(0xFF67E8F9),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun QualityBadge(tag: String) {
    Text(
        text = tag,
        color = Color(0xFF67E8F9),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0EA5E9).copy(alpha = 0.2f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun PeerSlotBadge(hasFreeSlot: Boolean, queueLength: Int) {
    Text(
        text = if (hasFreeSlot) "Free Slot" else "Queue: $queueLength",
        color = if (hasFreeSlot) Color(0xFF6EE7B7) else Color(0xFFFDE68A),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (hasFreeSlot) Color(0xFF10B981).copy(alpha = 0.2f)
                else Color(0xFFF59E0B).copy(alpha = 0.2f)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun EmptyHubHint(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color(0xFF9CA3AF), fontSize = 13.sp)
    }
}
