package com.muse.player.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muse.player.data.HistoryRepository
import com.muse.player.data.SettingsRepository
import com.muse.player.data.VideoItem
import com.muse.player.ui.player.PlayerLauncher

/**
 * 文件夹内的视频列表，直接嵌在媒体库分栏中展示（导航栏保持可见），
 * 搜索关键词与排序由媒体库顶栏统一控制。
 */
@Composable
fun FolderContent(
    viewModel: LibraryViewModel,
    bucketId: String,
    query: String,
    modifier: Modifier = Modifier,
    resumeTick: Int = 0
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val allVideos = remember(bucketId, state.videos, state.sortField, state.ascending) {
        viewModel.videosInFolder(bucketId)
    }
    val videos = remember(allVideos, query) {
        if (query.isBlank()) {
            allVideos
        } else {
            allVideos.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }
    }
    val progressMap = remember(state.history) {
        state.history.associate { it.uri to it.positionMs }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val wideGridState = rememberLazyGridState()

    // 打开文件夹或回到前台时，滚动到该文件夹里上次播放的视频
    LaunchedEffect(
        settings.scrollToLastPlayed,
        bucketId,
        query,
        state.loading,
        state.history.firstOrNull()?.uri,
        resumeTick
    ) {
        if (!settings.scrollToLastPlayed || query.isNotBlank() || state.loading) {
            return@LaunchedEffect
        }
        val lastUri = state.history.firstOrNull()?.uri ?: return@LaunchedEffect
        val index = videos.indexOfFirst { it.uri.toString() == lastUri }
        if (index <= 0) return@LaunchedEffect
        when {
            settings.gridViewMode -> gridState.scrollToItem(index)
            wide -> wideGridState.scrollToItem(index)
            else -> listState.scrollToItem(index)
        }
    }

    fun play(video: VideoItem, fromStart: Boolean = false) {
        if (videos.isEmpty()) return
        val entries = videos.map { it.toPlayEntry() }
        val index = videos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val resume = if (!fromStart && SettingsRepository.state.value.autoResume) {
            HistoryRepository.progressOf(video.uri.toString())
        } else {
            0L
        }
        PlayerLauncher.open(context, entries, index, resume)
    }

    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (settings.gridViewMode) {
            LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(200.dp) else GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(if (wide) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp)
            ) {
                gridItems(videos, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        progressMs = progressMap[video.uri.toString()] ?: 0L,
                        onClick = { play(video) },
                        onLongClick = { actionVideo = video }
                    )
                }
            }
        } else if (wide) {
            // 宽屏列表视图：多列排布
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                state = wideGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(videos, key = { it.id }) { video ->
                    VideoRow(
                        video = video,
                        progressMs = progressMap[video.uri.toString()] ?: 0L,
                        onClick = { play(video) },
                        onLongClick = { actionVideo = video }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoRow(
                        video = video,
                        progressMs = progressMap[video.uri.toString()] ?: 0L,
                        onClick = { play(video) },
                        onLongClick = { actionVideo = video }
                    )
                }
            }
        }
    }

    actionVideo?.let { video ->
        ActionSheet(
            title = video.title,
            actions = listOf(
                MenuAction("播放", Icons.Rounded.PlayArrow) { play(video) },
                MenuAction("从头播放", Icons.Rounded.Replay) { play(video, fromStart = true) },
                MenuAction("复制路径", Icons.Rounded.ContentCopy) {
                    val text = video.path ?: video.uri.toString()
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText("Muse Player", text))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            ),
            onDismiss = { actionVideo = null }
        )
    }
}
