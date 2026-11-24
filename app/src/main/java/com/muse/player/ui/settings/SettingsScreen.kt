package com.muse.player.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muse.player.BuildConfig
import com.muse.player.data.SettingsRepository
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("外观")
            listOf(
                0 to "跟随系统",
                1 to "浅色主题",
                2 to "深色主题"
            ).forEach { (mode, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { SettingsRepository.setThemeMode(mode) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, modifier = Modifier.weight(1f))
                    RadioButton(
                        selected = settings.themeMode == mode,
                        onClick = { SettingsRepository.setThemeMode(mode) }
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("媒体库")
            SwitchRow(
                title = "显示隐藏文件夹",
                subtitle = "开启后，已隐藏文件夹中的视频会重新出现在媒体库",
                checked = settings.showHiddenFolders,
                onCheckedChange = SettingsRepository::setShowHiddenFolders
            )
            SwitchRow(
                title = "显示系统隐藏文件夹",
                subtitle = "扫描以 . 开头的系统隐藏目录（如 .secret）内的视频",
                checked = settings.showSystemHiddenFolders,
                onCheckedChange = SettingsRepository::setShowSystemHiddenFolders
            )
            if (settings.showSystemHiddenFolders && !allFilesAccess) {
                ActionRow(
                    title = "授予所有文件访问权限",
                    subtitle = "Android 11 及以上需要该权限才能读取 . 开头的目录",
                    onClick = { openAllFilesAccess(context) }
                )
            }
            SwitchRow(
                title = "低性能模式",
                subtitle = "不加载视频缩略图，只显示纯色占位，老机型滚动更流畅",
                checked = settings.lowPerformanceMode,
                onCheckedChange = SettingsRepository::setLowPerformanceMode
            )
            SwitchRow(
                title = "滚动到上次播放",
                subtitle = "打开媒体库时自动滚动到上次播放的视频位置",
                checked = settings.scrollToLastPlayed,
                onCheckedChange = SettingsRepository::setScrollToLastPlayed
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("导航栏")
            SwitchRow(
                title = "显示媒体库分栏",
                subtitle = "本地视频、文件夹与最近播放的入口",
                checked = "MEDIA" !in settings.hiddenTabs,
                onCheckedChange = { SettingsRepository.setTabVisible("MEDIA", it) }
            )
            SwitchRow(
                title = "显示历史分栏",
                subtitle = "关闭后底部 / 侧边导航栏不再出现历史记录",
                checked = "HISTORY" !in settings.hiddenTabs,
                onCheckedChange = { SettingsRepository.setTabVisible("HISTORY", it) }
            )
            SwitchRow(
                title = "显示浏览分栏",
                subtitle = "关闭后不再显示系统文件夹浏览入口",
                checked = "BROWSE" !in settings.hiddenTabs,
                onCheckedChange = { SettingsRepository.setTabVisible("BROWSE", it) }
            )
            SwitchRow(
                title = "显示网络分栏",
                subtitle = "局域网 SMB / FTP / WebDAV 与流媒体地址入口",
                checked = "NETWORK" !in settings.hiddenTabs,
                onCheckedChange = { SettingsRepository.setTabVisible("NETWORK", it) }
            )
            Text(
                text = "分栏顺序",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
            )
            Text(
                text = "长按分栏上下拖动即可调整顺序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            TabOrderList(settings.tabOrder)
            Text(
                text = "至少保留一个分栏，最后一个开启的分栏无法关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("播放")
            SwitchRow(
                title = "自动续播",
                subtitle = "从上次退出的位置继续播放",
                checked = settings.autoResume,
                onCheckedChange = SettingsRepository::setAutoResume
            )
            SwitchRow(
                title = "后台播放",
                subtitle = "离开播放器后继续播放声音",
                checked = settings.backgroundPlay,
                onCheckedChange = SettingsRepository::setBackgroundPlay
            )
            SwitchRow(
                title = "通知栏播放卡片",
                subtitle = "在通知栏和锁屏显示媒体控制卡片；关闭后不显示（部分系统可能限制后台播放）",
                checked = settings.showMediaNotification,
                onCheckedChange = SettingsRepository::setShowMediaNotification
            )
            SwitchRow(
                title = "自动加载同名字幕",
                subtitle = "自动搜索视频所在目录中的 srt / ass / vtt 字幕；字号与时间偏移可在播放页的字幕面板中调整",
                checked = settings.autoSubtitles,
                onCheckedChange = SettingsRepository::setAutoSubtitles
            )
            SeekSensitivityControl()
            LongPressSpeedControl()
            SwitchRow(
                title = "长按画面上下滑动调速",
                subtitle = "长按画面后上下滑动增减倍速；关闭时长按画面仅临时倍速播放",
                checked = settings.longPressSwipeSpeed,
                onCheckedChange = SettingsRepository::setLongPressSwipeSpeed
            )
            DoubleTapSeekControl()

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("解码")
            DecoderOptionRow(
                title = "自动（推荐）",
                subtitle = "优先硬件解码，失败时自动回退到软件解码",
                selected = settings.decoderMode == 0,
                onClick = { SettingsRepository.setDecoderMode(0) }
            )
            DecoderOptionRow(
                title = "强制软件解码",
                subtitle = "兼容性最好，黑屏、花屏或无法播放时可尝试",
                selected = settings.decoderMode == 1,
                onClick = { SettingsRepository.setDecoderMode(1) }
            )
            DecoderOptionRow(
                title = "强制硬件解码",
                subtitle = "性能最好、更省电，解码失败时不回退",
                selected = settings.decoderMode == 2,
                onClick = { SettingsRepository.setDecoderMode(2) }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("关于")
            ActionRow(
                title = "关于 Muse Player",
                subtitle = "版本 ${BuildConfig.VERSION_NAME} · 开源许可与项目信息",
                onClick = onOpenAbout
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SeekSensitivityControl() {
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    var seconds by remember(settings.seekGestureSeconds) {
        mutableFloatStateOf(settings.seekGestureSeconds.toFloat())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("左右滑动调进度灵敏度", modifier = Modifier.weight(1f))
            Text(
                text = "${seconds.toInt()} 秒 / 全屏",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueChangeFinished = {
                SettingsRepository.setSeekGestureSeconds(seconds.toInt())
            },
            valueRange = 60f..3600f
        )
        Text(
            text = "数值越大，滑动同样距离调节的时间越长；默认 300 秒，最大 3600 秒（60 分钟）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LongPressSpeedControl() {
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    var speed by remember(settings.longPressSpeed) {
        mutableFloatStateOf(settings.longPressSpeed)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("长按倍速播放", modifier = Modifier.weight(1f))
            Text(
                text = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = speed,
            onValueChange = { speed = (kotlin.math.round(it * 2f) / 2f) },
            onValueChangeFinished = { SettingsRepository.setLongPressSpeed(speed) },
            valueRange = 2f..8f,
            steps = 11
        )
        Text(
            text = "长按画面以所选倍速播放，松手恢复原速；默认 2x，可选 2x–8x",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DoubleTapSeekControl() {
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    var seconds by remember(settings.doubleTapSeekSeconds) {
        mutableFloatStateOf(settings.doubleTapSeekSeconds.toFloat())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("双击快进 / 快退", modifier = Modifier.weight(1f))
            Text(
                text = "${seconds.toInt()} 秒",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueChangeFinished = { SettingsRepository.setDoubleTapSeekSeconds(seconds.toInt()) },
            valueRange = 5f..60f,
            steps = 10
        )
        Text(
            text = "双击画面左侧后退、右侧前进的秒数；默认 10 秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DecoderOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = MaterialTheme.colorScheme.primary)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appIntent = Intent(
        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    try {
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: Exception) {
        }
    }
}

/**
 * 长按拖动调整分栏顺序。
 * 每一项用 key(tabKey) 固定身份，拖动过程中顺序变化不会中断手势。
 */
@Composable
private fun TabOrderList(order: List<String>) {
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = 52.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    Column {
        order.forEach { tabKey ->
            key(tabKey) {
                val dragging = draggingKey == tabKey
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                        }
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            }
                        )
                        .pointerInput(tabKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = tabKey
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val current = SettingsRepository.state.value.tabOrder
                                    val from = current.indexOf(tabKey)
                                    if (from != -1) {
                                        val target = (from + (dragOffset / rowHeightPx).roundToInt())
                                            .coerceIn(0, current.lastIndex)
                                        if (target != from) {
                                            SettingsRepository.moveTabTo(tabKey, target)
                                            dragOffset -= (target - from) * rowHeightPx
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                }
                            )
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(text = tabTitle(tabKey), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun tabTitle(key: String): String = when (key) {
    "MEDIA" -> "媒体库"
    "HISTORY" -> "历史"
    "BROWSE" -> "浏览"
    "NETWORK" -> "网络"
    else -> key
}
