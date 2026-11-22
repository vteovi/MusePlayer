package com.muse.player.ui.library

import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavController
import com.muse.player.data.FolderItem
import com.muse.player.data.HistoryEntry
import com.muse.player.data.HistoryRepository
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SortField
import com.muse.player.data.VideoItem
import com.muse.player.ui.network.NetworkTab
import com.muse.player.ui.network.NetworkViewModel
import com.muse.player.ui.player.PlayerLauncher
import com.muse.player.util.MediaPermissions
import com.muse.player.util.NetworkTitles
import com.muse.player.util.formatDate
import com.muse.player.util.formatDuration
import com.muse.player.util.formatSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel,
    networkViewModel: NetworkViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appSettings by SettingsRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(MediaPermissions.granted(context)) }
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var infoVideo by remember { mutableStateOf<VideoItem?>(null) }
    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }
    var actionFolder by remember { mutableStateOf<FolderItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = MediaPermissions.granted(context)
        if (hasPermission) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        val permissions = MediaPermissions.required() +
            listOfNotNull(MediaPermissions.notificationPermission())
        if (!hasPermission) permissionLauncher.launch(permissions)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = MediaPermissions.granted(context)
                if (granted != hasPermission) {
                    hasPermission = granted
                    if (granted) viewModel.refresh()
                }
                // 从系统设置授予“所有文件访问权限”返回后，重新扫描系统隐藏目录
                val allFiles = hasAllFilesAccess()
                if (allFiles != allFilesAccess) {
                    allFilesAccess = allFiles
                    if (allFiles && SettingsRepository.state.value.showSystemHiddenFolders) {
                        viewModel.refresh()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission && state.tab != 3) {
        PermissionScreen(
            onRequest = {
                permissionLauncher.launch(
                    MediaPermissions.required() +
                        listOfNotNull(MediaPermissions.notificationPermission())
                )
            },
            onOpenSettings = { openAppSettings(context) },
            // 网络分栏被隐藏时不显示入口
            onBrowseNetwork = if ("NETWORK" in appSettings.hiddenTabs) {
                null
            } else {
                { viewModel.setTab(3) }
            }
        )
        return
    }

    fun playVideo(video: VideoItem, queue: List<VideoItem>, fromStart: Boolean = false) {
        if (queue.isEmpty()) return
        val entries = queue.map { it.toPlayEntry() }
        val index = queue.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val resume = if (!fromStart && SettingsRepository.state.value.autoResume) {
            HistoryRepository.progressOf(video.uri.toString())
        } else {
            0L
        }
        PlayerLauncher.open(context, entries, index, resume)
    }

    val allTabs = remember {
        listOf(
            LibraryTab(0, "MEDIA", "媒体库", Icons.Rounded.VideoLibrary),
            LibraryTab(1, "HISTORY", "历史", Icons.Rounded.History),
            LibraryTab(2, "BROWSE", "浏览", Icons.Rounded.FolderOpen),
            LibraryTab(3, "NETWORK", "网络", Icons.Rounded.Dns)
        )
    }
    // 历史/浏览/网络可以在设置里隐藏，媒体库始终保留
    val tabs = remember(appSettings.hiddenTabs) {
        allTabs.filter { it.key == "MEDIA" || it.key !in appSettings.hiddenTabs }
    }
    // 媒体库视图模式：0 = 视频，1 = 文件夹（持久化，下次打开保持上次选择）
    var mediaMode by rememberSaveable {
        mutableIntStateOf(if (SettingsRepository.state.value.folderViewMode) 1 else 0)
    }
    // 文件夹在当前分栏内展开，导航栏与顶栏始终可见
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = openFolderId != null) {
        openFolderId = null
        viewModel.setQuery("")
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // 分栏可能被隐藏，逻辑页码与可见页码需要互相换算
    val initialPage = remember { tabs.indexOfFirst { it.index == state.tab }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { tabs.size }

    val scope = rememberCoroutineScope()

    // 页码停稳后再同步 tab：滑动过程中写回状态会让整页重组，手势会掉帧
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val logical = tabs.getOrNull(page)?.index ?: return@collect
            if (logical != viewModel.state.value.tab) viewModel.setTab(logical)
        }
    }

    // 设置中隐藏了当前分栏时，切回第一个可见分栏
    LaunchedEffect(tabs) {
        val target = tabs.indexOfFirst { it.index == viewModel.state.value.tab }
        if (target == -1) {
            viewModel.setTab(tabs.first().index)
            pagerState.scrollToPage(0)
        } else if (target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    if (openFolderId != null) {
                        IconButton(onClick = {
                            openFolderId = null
                            viewModel.setQuery("")
                        }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = {
                    val folderName = openFolderId?.let { id ->
                        state.folders.firstOrNull { it.bucketId == id }?.name
                    }
                    Text(
                        text = folderName ?: "Muse Player",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setQuery("")
                    }) {
                        Icon(
                            if (showSearch) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "搜索"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Rounded.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortField.entries.forEach { field ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            field.label + if (state.sortField == field) {
                                                if (state.ascending) "  ↑" else "  ↓"
                                            } else {
                                                ""
                                            }
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortField(field)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (state.sortField == field) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            if (state.tab == 0) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (mediaMode == 0) "文件夹视图"
                                            else "视频视图"
                                        )
                                    },
                                    onClick = {
                                        mediaMode = if (mediaMode == 0) 1 else 0
                                        SettingsRepository.setFolderViewMode(mediaMode == 1)
                                        showOverflow = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (mediaMode == 0) Icons.Rounded.Folder
                                            else Icons.Rounded.VideoLibrary,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (state.grid) "列表视图" else "网格视图") },
                                onClick = {
                                    viewModel.toggleGrid()
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(
                                        if (state.grid) Icons.Rounded.ViewList
                                        else Icons.Rounded.GridView,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("网络串流") },
                                onClick = {
                                    showNetworkDialog = true
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Link, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("刷新媒体库") },
                                onClick = {
                                    viewModel.refresh()
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    navController.navigate("settings")
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 窄屏把分栏放到屏幕底部，拇指更容易触达
            if (!wide) {
                LibraryBottomBar(
                    pagerState = pagerState,
                    tabs = tabs,
                    onSelect = { page ->
                        tabs.getOrNull(page)?.let { viewModel.setTab(it.index) }
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                )
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // 导航与标签栏单独成组件：翻页进度变化只重组它们自己，不连带整页
            if (wide) {
                LibraryNavRail(
                    pagerState = pagerState,
                    tabs = tabs,
                    onSelect = { page ->
                        tabs.getOrNull(page)?.let { viewModel.setTab(it.index) }
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                    onSettings = { navController.navigate("settings") }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
            if (showSearch) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索视频名称") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                    }
                )
            }

            val progressMap = remember(state.history) {
                state.history.associate { it.uri to it.positionMs }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (tabs.getOrNull(page)?.index ?: 0) {
                0 -> {
                    val openFolder = openFolderId
                    if (mediaMode == 1 && openFolder != null) {
                        FolderContent(
                            viewModel = viewModel,
                            bucketId = openFolder,
                            query = state.query,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (mediaMode == 1) {
                        if (state.folders.isEmpty()) {
                            EmptyState("没有文件夹", "视频会按照所在目录归入文件夹")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(state.folders, key = { it.bucketId }) { folder ->
                                    FolderRow(
                                        folder = folder,
                                        onClick = {
                                            viewModel.setQuery("")
                                            openFolderId = folder.bucketId
                                        },
                                        hidden = folder.bucketId in state.hiddenFolderIds,
                                        onLongClick = { actionFolder = folder }
                                    )
                                }
                            }
                        }
                    } else when {
                        state.loading -> LoadingBox()
                        state.videos.isEmpty() -> EmptyState(
                            title = if (state.filterActive) "没有匹配的视频" else "媒体库为空",
                            subtitle = if (state.filterActive) {
                                "试试其他关键词"
                            } else {
                                "将视频拷贝到设备后，下拉刷新或重新扫描"
                            }
                        )

                        state.grid -> LazyVerticalGrid(
                            columns = if (wide) {
                                GridCells.Adaptive(200.dp)
                            } else {
                                GridCells.Fixed(2)
                            },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.videos, key = { it.id }) { video ->
                                VideoCard(
                                    video = video,
                                    progressMs = progressMap[video.uri.toString()] ?: 0L,
                                    onClick = { playVideo(video, state.videos) },
                                    onLongClick = { actionVideo = video }
                                )
                            }
                        }

                        // 宽屏列表视图：多列排布，而不是拉成单列长条
                        wide -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(360.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.videos, key = { it.id }) { video ->
                                VideoRow(
                                    video = video,
                                    progressMs = progressMap[video.uri.toString()] ?: 0L,
                                    onClick = { playVideo(video, state.videos) },
                                    onLongClick = { actionVideo = video }
                                )
                            }
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(state.videos, key = { it.id }) { video ->
                                VideoRow(
                                    video = video,
                                    progressMs = progressMap[video.uri.toString()] ?: 0L,
                                    onClick = { playVideo(video, state.videos) },
                                    onLongClick = { actionVideo = video }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (state.history.isEmpty()) {
                        EmptyState("暂无播放记录", "播放过的视频会出现在这里")
                    } else {
                        // 媒体库可能有几千条，建 uri 索引放到后台线程，
                        // 避免滑动进入历史分栏的那一帧在主线程上做大量映射
                        var videoByUri by remember {
                            mutableStateOf<Map<String, VideoItem>>(emptyMap())
                        }
                        LaunchedEffect(state.videos) {
                            val videos = state.videos
                            videoByUri = withContext(Dispatchers.Default) {
                                videos.associateBy { it.uri.toString() }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${state.history.size} 条记录",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = viewModel::clearHistory) {
                                        Text("清空")
                                    }
                                }
                            }
                            items(state.history, key = { it.uri }) { entry ->
                                HistoryRow(
                                    entry = entry,
                                    video = videoByUri[entry.uri],
                                    onClick = { playHistory(entry, videoByUri[entry.uri], context) },
                                    onRemove = { viewModel.removeHistory(entry.uri) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    BrowseTab(
                        onPlay = { queue, index ->
                            if (queue.isNotEmpty()) {
                                val entries = queue.map { it.toPlayEntry() }
                                val resume = if (SettingsRepository.state.value.autoResume) {
                                    HistoryRepository.progressOf(
                                        queue[index].uri.toString()
                                    )
                                } else {
                                    0L
                                }
                                PlayerLauncher.open(context, entries, index, resume)
                            }
                        }
                    )
                }

                else -> {
                    NetworkTab(viewModel = networkViewModel)
                }
                }
            }
            }
        }
    }

    if (showNetworkDialog) {
        NetworkDialog(
            onDismiss = { showNetworkDialog = false },
            onPlay = { url ->
                val title = NetworkTitles.of(url)
                PlayerLauncher.open(
                    context,
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
                showNetworkDialog = false
            }
        )
    }

    actionFolder?.let { folder ->
        val videos = viewModel.videosInFolder(folder.bucketId)
        val hidden = folder.bucketId in state.hiddenFolderIds
        ActionSheet(
            title = folder.name,
            actions = listOf(
                MenuAction("播放全部", Icons.Rounded.PlayArrow) {
                    videos.firstOrNull()?.let { playVideo(it, videos, fromStart = true) }
                },
                MenuAction("随机播放全部", Icons.Rounded.Shuffle) {
                    val shuffled = videos.shuffled()
                    shuffled.firstOrNull()?.let { playVideo(it, shuffled, fromStart = true) }
                },
                MenuAction(
                    label = if (hidden) "取消隐藏文件夹" else "隐藏文件夹",
                    icon = if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
                ) {
                    viewModel.toggleFolderHidden(folder.bucketId)
                }
            ),
            onDismiss = { actionFolder = null }
        )
    }

    actionVideo?.let { video ->
        val queue = viewModel.videosInFolder(video.bucketId)
            .ifEmpty { state.videos }
        val openFolder = state.folders.any { it.bucketId == video.bucketId }
        ActionSheet(
            title = video.title,
            actions = listOfNotNull(
                MenuAction("播放", Icons.Rounded.PlayArrow) {
                    playVideo(video, queue)
                },
                MenuAction("从头播放", Icons.Rounded.Replay) {
                    playVideo(video, queue, fromStart = true)
                },
                MenuAction("查看信息", Icons.Rounded.Info) {
                    infoVideo = video
                },
                MenuAction("复制路径", Icons.Rounded.ContentCopy) {
                    val text = video.path ?: video.uri.toString()
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText("Muse Player", text))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
                if (openFolder) {
                    MenuAction("打开所在文件夹", Icons.Rounded.FolderOpen) {
                        mediaMode = 1
                        SettingsRepository.setFolderViewMode(true)
                        viewModel.setQuery("")
                        openFolderId = video.bucketId
                    }
                } else {
                    null
                }
            ),
            onDismiss = { actionVideo = null }
        )
    }

    infoVideo?.let { video ->
        VideoInfoDialog(video = video, onDismiss = { infoVideo = null })
    }
}

/** 读取剪贴板中的播放地址，非网络链接或读取失败时返回 null */
private fun readClipboardUrl(context: Context): String? {
    return try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
        val scheme = text?.substringBefore(':', "")?.lowercase() ?: return null
        if (text.length > 2048) return null
        if (scheme in setOf("http", "https", "rtsp", "rtmps", "rtmp", "mms", "ftp")) text else null
    } catch (_: Exception) {
        null
    }
}

private fun playHistory(entry: HistoryEntry, video: VideoItem?, context: Context) {
    val playEntry = PlayEntry(
        uri = entry.uri,
        title = entry.title,
        id = video?.id ?: -1L,
        durationMs = entry.durationMs,
        path = video?.path,
        isNetwork = isNetworkUri(entry.uri)
    )
    val resume = if (SettingsRepository.state.value.autoResume) entry.positionMs else 0L
    PlayerLauncher.open(context, listOf(playEntry), 0, resume)
}

/** 判断历史记录中的地址是否为网络 / 局域网 / 流媒体资源 */
private fun isNetworkUri(uri: String): Boolean {
    val scheme = uri.substringBefore(':', "").lowercase()
    return scheme.startsWith("muse+") ||
        scheme in setOf("http", "https", "rtsp", "rtmps", "rtmp", "mms", "ftp")
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PermissionScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBrowseNetwork: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text("需要视频访问权限", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Muse Player 需要读取设备上的视频，才能建立媒体库并播放。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text("授予权限")
        }
        TextButton(onClick = onOpenSettings) {
            Text("前往系统设置")
        }
        if (onBrowseNetwork != null) {
            TextButton(onClick = onBrowseNetwork) {
                Text("局域网浏览")
            }
        }
    }
}

@Composable
private fun NetworkDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 打开对话框时自动填入剪贴板中的播放地址
    LaunchedEffect(Unit) {
        val clip = readClipboardUrl(context)
        if (clip != null && url.isBlank()) url = clip
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开网络串流") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/video.m3u8") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "支持 HTTP / HTTPS / HLS / DASH / RTSP 地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = url.trim()
                if (trimmed.startsWith("http://") ||
                    trimmed.startsWith("https://") ||
                    trimmed.startsWith("rtsp://") ||
                    trimmed.startsWith("rtmp://")
                ) {
                    onPlay(trimmed)
                } else {
                    error = "请输入合法的流媒体地址"
                }
            }) {
                Text("播放")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun VideoInfoDialog(video: VideoItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = video.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InfoLine("时长", formatDuration(video.durationMs))
                InfoLine("大小", formatSize(video.size))
                if (video.width > 0) {
                    InfoLine("分辨率", "${video.width} × ${video.height}")
                }
                InfoLine("加入时间", formatDate(video.dateAdded))
                InfoLine("文件夹", video.bucketName)
                InfoLine("路径", video.path ?: video.uri.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private data class LibraryTab(
    val index: Int,
    val key: String,
    val title: String,
    val icon: ImageVector
)

/**
 * 宽屏左侧导航栏。翻页进度只在这个组件内部读取，
 * 滑动时不会因为 currentPage 变化而重组媒体库/历史等页面内容。
 * 视觉与底部栏一致：无指示器背景、无水波纹，选中只靠图标/文字变色。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryNavRail(
    pagerState: PagerState,
    tabs: List<LibraryTab>,
    onSelect: (Int) -> Unit,
    onSettings: () -> Unit
) {
    val currentPage = { pagerState.currentPage }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(80.dp)
                .selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            tabs.forEachIndexed { index, tab ->
                RailNavItem(
                    icon = tab.icon,
                    label = tab.title,
                    activeProvider = { currentPage() == index },
                    onClick = { onSelect(index) }
                )
            }
            Spacer(Modifier.weight(1f))
            // 宽屏端在导航栏底部提供设置入口
            RailNavItem(
                icon = Icons.Rounded.Settings,
                label = "设置",
                activeProvider = { false },
                onClick = onSettings
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RailNavItem(
    icon: ImageVector,
    label: String,
    activeProvider: () -> Boolean,
    onClick: () -> Unit
) {
    val active by remember { derivedStateOf { activeProvider() } }
    val interactionSource = remember { MutableInteractionSource() }
    NavItemVisual(
        icon = icon,
        label = label,
        active = active,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .selectable(
                selected = active,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
    )
}

/**
 * 窄屏底部导航栏。
 * 不用 Material3 NavigationBar：它带指示器动画和涟漪，低端机左右滑动切换时容易掉帧；
 * 这里自绘固定 56dp 的轻量栏，选中态只在翻转时重组。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryBottomBar(
    pagerState: PagerState,
    tabs: List<LibraryTab>,
    onSelect: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp)
                .selectableGroup()
        ) {
            val currentPage = { pagerState.currentPage }
            tabs.forEachIndexed { index, tab ->
                BottomNavItem(
                    tab = tab,
                    currentPage = currentPage,
                    index = index,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    tab: LibraryTab,
    currentPage: () -> Int,
    index: Int,
    onClick: () -> Unit
) {
    val active by remember { derivedStateOf { currentPage() == index } }
    val interactionSource = remember { MutableInteractionSource() }
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = active,
                // 去掉水波纹：微信那种只靠图标/文字变色表示选中，少一层涟漪绘制
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.title,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = tab.title,
            color = color,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 导航项统一视觉：图标 + 文字，选中仅变色 */
@Composable
private fun NavItemVisual(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
