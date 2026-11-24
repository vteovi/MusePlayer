package com.muse.player.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.graphics.Bitmap
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.muse.player.R
import com.muse.player.data.SettingsRepository
import com.muse.player.data.ZoomRepository
import com.muse.player.util.SeekPreviewLoader
import com.muse.player.util.formatDuration
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GestureType { BRIGHTNESS, VOLUME, SEEK, INFO }

private data class GestureHint(val type: GestureType, val text: String)

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x"
    else String.format(Locale.US, "%.2fx", speed)

/** 设置中的字幕字号档位对应到 sp */
private fun subtitleTextSizeSp(size: Int): Float = when (size) {
    0 -> 14f
    1 -> 18f
    2 -> 22f
    else -> 27f
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    // 设置页覆盖在播放页之上时，播放页不响应返回键
    backHandlerEnabled: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // 锁屏后解锁按钮只短暂显示，点击画面再唤出
    var lockHintVisible by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // 按视频分别记住缩放倍数：切走时存下当前值，切到这个视频时恢复它自己的值
    val zoomByVideo = remember { mutableStateMapOf<String, Float>() }
    var zoomKey by remember { mutableStateOf(state.currentUri) }
    LaunchedEffect(state.currentUri) {
        if (state.currentUri == zoomKey) return@LaunchedEffect
        if (zoomKey.isNotEmpty()) {
            zoomByVideo[zoomKey] = zoom
            ZoomRepository.put(zoomKey, zoom)
        }
        zoomKey = state.currentUri
        zoom = zoomByVideo[state.currentUri]
            ?: ZoomRepository.get(state.currentUri)
            ?: 1f
    }
    // 缩放停止变化后落盘，避免捏合过程中反复写磁盘
    LaunchedEffect(zoom, zoomKey) {
        if (zoomKey.isEmpty()) return@LaunchedEffect
        delay(500)
        ZoomRepository.put(zoomKey, zoom)
    }
    // 离开播放页时保存当前视频的缩放
    DisposableEffect(Unit) {
        onDispose {
            if (zoomKey.isNotEmpty()) ZoomRepository.put(zoomKey, zoom)
        }
    }
    var gestureHint by remember { mutableStateOf<GestureHint?>(null) }
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    // 长按倍速按钮调速进行中：控制栏保持显示，手松开才隐藏
    var speedDragging by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showAspectSheet by remember { mutableStateOf(false) }
    var surfaceRotation by remember { mutableIntStateOf(0) }
    var showCustomRatioDialog by remember { mutableStateOf(false) }
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    // 字幕字号变化时同步给兼容引擎（libVLC 需重新打开媒体才能应用新字号）
    LaunchedEffect(settings.subtitleSize) {
        viewModel.applySubtitleSize()
    }

    // 拖动进度时的画面预览
    var previewVisible by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewTimeMs by remember { mutableStateOf(0L) }
    var previewBusy by remember { mutableStateOf(false) }
    val previewScope = rememberCoroutineScope()
    val previewLoader = remember { SeekPreviewLoader(context) }
    DisposableEffect(Unit) {
        onDispose { previewLoader.release() }
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val window = activity?.window

    fun currentBrightness(): Float {
        val attr = window?.attributes?.screenBrightness ?: -1f
        if (attr >= 0f) return attr
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    fun applyBrightness(value: Float) {
        val w = window ?: return
        val lp = w.attributes
        lp.screenBrightness = value.coerceIn(0.01f, 1f)
        w.attributes = lp
    }

    val cycleOrientation: () -> Unit = {
        val current = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val next = when (current) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activity?.requestedOrientation = next
        gestureHint = GestureHint(
            GestureType.INFO,
            when (next) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "锁定横屏"
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "锁定竖屏"
                else -> "自动旋转"
            }
        )
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.addSubtitle(uri, queryDisplayName(context, uri))
            gestureHint = GestureHint(GestureType.INFO, "已加载字幕")
        }
    }

    BackHandler(enabled = backHandlerEnabled) {
        if (locked) {
            locked = false
            controlsVisible = true
        } else {
            viewModel.saveProgress()
            onBack()
        }
    }

    // 全屏沉浸式 + 屏幕常亮
    DisposableEffect(Unit) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let {
                val lp = it.attributes
                lp.screenBrightness = -1f
                it.attributes = lp
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 控制栏显示时一并显示状态栏和导航栏（虚拟按键），隐藏控制栏或锁屏时恢复沉浸式
    LaunchedEffect(controlsVisible, locked, backHandlerEnabled) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        if (!backHandlerEnabled) {
            // 设置页覆盖在播放器之上时保持系统栏可见，避免其顶栏被状态栏遮挡
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.show(WindowInsetsCompat.Type.systemBars())
            return@LaunchedEffect
        }
        if (controlsVisible && !locked) {
            // 黑底上必须用浅色图标，否则系统栏内容会变成黑色导致看不清
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 进入后台（含画中画）时隐藏控制栏
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controlsVisible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 锁屏时解锁按钮自动隐藏，触摸画面后重新显示几秒
    LaunchedEffect(locked, lockHintVisible) {
        if (locked && lockHintVisible) {
            delay(2500)
            lockHintVisible = false
        }
    }

    // 控制栏自动隐藏
    LaunchedEffect(controlsVisible, state.isPlaying, locked, showSpeedSheet, showAudioSheet, showSubtitleSheet, showAspectSheet, speedDragging) {
        if (controlsVisible && state.isPlaying && !locked && !speedDragging &&
            !showSpeedSheet && !showAudioSheet && !showSubtitleSheet && !showAspectSheet
        ) {
            delay(4500)
            controlsVisible = false
        }
    }

    // 提示信息自动消失
    LaunchedEffect(gestureHint) {
        val current = gestureHint
        if (current != null && current.type == GestureType.INFO) {
            delay(1200)
            if (gestureHint == current) gestureHint = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val rotated = surfaceRotation % 180 != 0
            val ratio = aspectRatioFor(
                state.aspectMode,
                state.customAspectWidth,
                state.customAspectHeight
            )
            // 交换宽高时按旋转后的显示区域计算尺寸，画面本身再旋转 90°
            val displayRatio = when {
                ratio == null -> null
                rotated -> 1f / ratio
                else -> ratio
            }
            val videoModifier = if (displayRatio == null) {
                Modifier.fillMaxSize()
            } else {
                val screenRatio = maxWidth / maxHeight
                if (displayRatio >= screenRatio) {
                    Modifier.width(maxWidth).height(maxWidth / displayRatio)
                } else {
                    Modifier.height(maxHeight).width(maxHeight * displayRatio)
                }
            }
            if (state.connected) {
                if (state.engine == 1) {
                    // 兼容引擎（libVLC）自带画面输出布局
                    key(rotated) {
                        AndroidView(
                            factory = { ctx ->
                                VLCVideoLayout(ctx).also { view ->
                                    view.keepScreenOn = true
                                    viewModel.attachVlcView(view)
                                }
                            },
                            modifier = videoModifier
                                .rotateSurface(surfaceRotation)
                                .graphicsLayer(scaleX = zoom, scaleY = zoom)
                        )
                    }
                } else {
                    // 旋转画面时必须使用 TextureView：SurfaceView 无法应用旋转/缩放变换；
                    // 解码兜底重建播放器后画面输出需要重新绑定，因此也纳入 key
                    key(rotated, state.decoderRestart) {
                        AndroidView(
                            factory = { ctx ->
                                val view = if (rotated) {
                                    LayoutInflater.from(ctx)
                                        .inflate(R.layout.view_muse_player_texture, null) as PlayerView
                                } else {
                                    PlayerView(ctx)
                                }
                                view.apply {
                                    useController = false
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    player = viewModel.player
                                }
                            },
                            update = { view ->
                                view.player = viewModel.player
                                // 系统字幕样式常带黑色底框会挡画面，统一改成透明底 + 描边文字
                                view.subtitleView?.apply {
                                    setStyle(
                                        CaptionStyleCompat(
                                            android.graphics.Color.WHITE,
                                            android.graphics.Color.TRANSPARENT,
                                            android.graphics.Color.TRANSPARENT,
                                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                            android.graphics.Color.BLACK,
                                            null
                                        )
                                    )
                                    // 字幕字号统一用固定值：忽略字幕文件内嵌字号，
                                    // 否则 ASS 等按画面高度计算的字号在横竖屏下会不一样大
                                    setApplyEmbeddedFontSizes(false)
                                    setFixedTextSize(
                                        TypedValue.COMPLEX_UNIT_SP,
                                        subtitleTextSizeSp(settings.subtitleSize)
                                    )
                                }
                                // 固定比例（3 起）用拉伸填充，让画面真正变成该比例而不是只加黑边
                                view.resizeMode = when {
                                    state.aspectMode >= 3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    state.aspectMode == 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    state.aspectMode == 2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = videoModifier
                                .rotateSurface(surfaceRotation)
                                .graphicsLayer(scaleX = zoom, scaleY = zoom)
                        )
                    }
                }
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }

        // 手势层
        if (!locked) {
            // 记录最近一次双指操作的时间：双指手势结束后的一小段时间内仍让单指手势让路，
            // 避免双指缩放时误触发亮度/音量/进度/长按倍速
            val lastMultiTouchAt = remember { longArrayOf(0L) }
            fun multiTouchHeld(): Boolean {
                val t = lastMultiTouchAt[0]
                return t != 0L &&
                    android.os.SystemClock.uptimeMillis() - t < 250L
            }
            // 长按调速进行中（拖动/亮度/音量让路）
            val longPressActive = remember { booleanArrayOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 双指捏合缩放（调速改由长按上下滑动完成，两者不再争手势）
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var hasBase = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    lastMultiTouchAt[0] = android.os.SystemClock.uptimeMillis()
                                    if (!hasBase) {
                                        hasBase = true
                                    } else {
                                        val zoomChange = event.calculateZoom()
                                        if (zoomChange != 1f) {
                                            zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                                        }
                                    }
                                    // 双指期间消费事件，单指拖动/长按不会再响应
                                    event.changes.forEach { it.consume() }
                                } else {
                                    // 手指数变化后重新取基准点，避免位置跳变
                                    hasBase = false
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                val width = size.width
                                when {
                                    // 中间区域双击：暂停 / 继续
                                    offset.x > width * 0.3f && offset.x < width * 0.7f -> {
                                        val playing = viewModel.state.value.isPlaying
                                        viewModel.togglePlay()
                                        gestureHint = GestureHint(
                                            GestureType.INFO,
                                            if (playing) "已暂停" else "继续播放"
                                        )
                                    }
                                    offset.x < width / 2f -> {
                                        val seconds = SettingsRepository.state.value.doubleTapSeekSeconds
                                        viewModel.seekBy(-seconds * 1000L)
                                        gestureHint = GestureHint(GestureType.INFO, "后退 $seconds 秒")
                                    }
                                    else -> {
                                        val seconds = SettingsRepository.state.value.doubleTapSeekSeconds
                                        viewModel.seekBy(seconds * 1000L)
                                        gestureHint = GestureHint(GestureType.INFO, "前进 $seconds 秒")
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // 长按 + 上下滑动调速。单独实现而不是用 detectTapGestures 的
                        // onLongPress：后者在长按后会消费后续事件，拖动逻辑收不到。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var twoFingers = false
                            // 等待长按超时；期间移动超过触摸阈值、抬起或第二指落下都算放弃
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        twoFingers = true
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val moved = (change.position - down.position).getDistance()
                                    if (moved > viewConfiguration.touchSlop) break
                                }
                                true
                            }
                            if (ended == true || twoFingers) return@awaitEachGesture

                            val baseSpeed = viewModel.state.value.speed
                            val boostSpeed = SettingsRepository.state.value.longPressSpeed
                            val swipeSpeedEnabled =
                                SettingsRepository.state.value.longPressSwipeSpeed
                            longPressActive[0] = true
                            viewModel.setSpeed(boostSpeed)
                            gestureHint = GestureHint(
                                GestureType.INFO,
                                if (swipeSpeedEnabled) {
                                    "${formatSpeed(boostSpeed)} 倍速 · 上下滑动调节"
                                } else {
                                    "${formatSpeed(boostSpeed)} 倍速播放"
                                }
                            )
                            var accDy = 0f
                            var adjusted = false
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    cancelled = true
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                accDy += change.position.y - change.previousPosition.y
                                change.consume()
                                if (swipeSpeedEnabled && abs(accDy) > 6f) {
                                    // 向上滑加速、向下滑减速，滑满一屏约变化 1.5 倍速
                                    val raw = baseSpeed - accDy / size.height * 1.5f
                                    val speed = ((raw * 20f).roundToInt() / 20f)
                                        .coerceIn(0.5f, 3f)
                                    adjusted = true
                                    if (speed != viewModel.state.value.speed) {
                                        viewModel.setSpeed(speed)
                                    }
                                    gestureHint = GestureHint(
                                        GestureType.INFO,
                                        "${formatSpeed(speed)} 倍速"
                                    )
                                }
                            }
                            if (!adjusted || cancelled) {
                                // 只长按没滑动/被双指打断：恢复原倍速
                                viewModel.setSpeed(baseSpeed)
                            }
                            longPressActive[0] = false
                            gestureHint = null
                        }
                    }
                    .pointerInput(Unit) {
                        var dragMode = 0 // 1 亮度, 2 音量, 3 进度
                        var accDx = 0f
                        var accDy = 0f
                        var startBrightness = 0.5f
                        var startVolume = 0
                        var startPosition = 0L
                        var previewUri: Uri? = null
                        // 全屏宽度滑动可调节的时长，可在设置中调整
                        var seekRangeMs = 120_000L
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)

                        fun seekTarget(): Long {
                            val duration = viewModel.state.value.durationMs
                                .takeIf { it > 0 } ?: Long.MAX_VALUE
                            return (startPosition + (accDx / width * seekRangeMs).toLong())
                                .coerceIn(0L, duration)
                        }

                        fun requestPreview(target: Long) {
                            previewTimeMs = target
                            previewVisible = true
                            if (previewBusy) return
                            val uri = previewUri ?: return
                            previewBusy = true
                            previewScope.launch {
                                val frame = previewLoader.load(uri, target)
                                if (frame != null && previewVisible) previewBitmap = frame
                                previewBusy = false
                            }
                        }

                        detectDragGestures(
                            onDragStart = { offset ->
                                if (multiTouchHeld() || longPressActive[0]) {
                                    // 双指缩放或长按调速期间不接管单指拖动
                                    dragMode = -1
                                    return@detectDragGestures
                                }
                                dragMode = 0
                                accDx = 0f
                                accDy = 0f
                                previewUri = viewModel.player
                                    ?.currentMediaItem
                                    ?.localConfiguration
                                    ?.uri
                                startBrightness = currentBrightness()
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                startPosition = viewModel.state.value.positionMs
                                seekRangeMs =
                                    SettingsRepository.state.value.seekGestureSeconds * 1000L
                                if (offset.x < width / 2f) {
                                    gestureHint = GestureHint(
                                        GestureType.BRIGHTNESS,
                                        "${(startBrightness * 100).toInt()}%"
                                    )
                                } else {
                                    gestureHint = GestureHint(
                                        GestureType.VOLUME,
                                        "${startVolume * 100 / maxVolume}%"
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (multiTouchHeld() || longPressActive[0] || dragMode < 0) {
                                    // 双指/长按调速期间不再响应单指的亮度/音量/进度调节
                                    dragMode = -1
                                    return@detectDragGestures
                                }
                                accDx += dragAmount.x
                                accDy += dragAmount.y
                                if (dragMode == 0) {
                                    dragMode = when {
                                        abs(accDx) > 24f && abs(accDx) > abs(accDy) -> 3
                                        abs(accDy) > 24f -> 1
                                        else -> 0
                                    }
                                    if (dragMode == 1) {
                                        // 左半区调亮度，右半区调音量
                                        val startX = change.position.x - accDx
                                        dragMode = if (startX < width / 2f) 1 else 2
                                    }
                                }
                                when (dragMode) {
                                    1 -> {
                                        val value = (startBrightness - accDy / height * 4f)
                                            .coerceIn(0.01f, 1f)
                                        applyBrightness(value)
                                        gestureHint = GestureHint(
                                            GestureType.BRIGHTNESS,
                                            "${(value * 100).toInt()}%"
                                        )
                                    }

                                    2 -> {
                                        val value = (startVolume - accDy / height * maxVolume * 2.5f)
                                            .toInt().coerceIn(0, maxVolume)
                                        try {
                                            audioManager.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                value,
                                                0
                                            )
                                        } catch (_: Exception) {
                                        }
                                        gestureHint = GestureHint(
                                            GestureType.VOLUME,
                                            "${value * 100 / maxVolume.coerceAtLeast(1)}%"
                                        )
                                    }

                                    3 -> {
                                        val target = seekTarget()
                                        gestureHint = GestureHint(
                                            GestureType.SEEK,
                                            "${formatDuration(target)} / ${formatDuration(viewModel.state.value.durationMs)}"
                                        )
                                        requestPreview(target)
                                    }
                                }
                            },
                            onDragEnd = {
                                if (dragMode == 3) {
                                    viewModel.seekTo(seekTarget())
                                }
                                dragMode = 0
                                gestureHint = null
                                previewVisible = false
                                previewBitmap = null
                            },
                            onDragCancel = {
                                dragMode = 0
                                gestureHint = null
                                previewVisible = false
                                previewBitmap = null
                            }
                        )
                    }
            )
        }

        // 缓冲提示
        if (state.isBuffering && state.error == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }

        // 拖动进度时的画面预览（拿不到帧时整块隐藏，不留黑色占位）
        val previewFrame = previewBitmap
        if (previewVisible && previewFrame != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = previewFrame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(180.dp)
                        .height(101.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }
        }

        // 手势提示
        gestureHint?.let { hint ->
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (hint.type) {
                        GestureType.BRIGHTNESS -> Icons.Rounded.Brightness6
                        GestureType.VOLUME -> if (hint.text.startsWith("0%")) {
                            Icons.Rounded.VolumeOff
                        } else {
                            Icons.Rounded.VolumeUp
                        }

                        GestureType.SEEK -> Icons.Rounded.FastForward
                        GestureType.INFO -> Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(hint.text, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }

        // 系统栏纯黑背景：状态栏与手势条/虚拟按键区域不再透出视频画面
        if (controlsVisible && !locked) {
            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusInset)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navInset)
                    .background(Color.Black)
            )
        }

        // 控制层
        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        viewModel.saveProgress()
                        onBack()
                    }) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (state.itemCount > 1) {
                            Text(
                                text = "${state.itemIndex + 1} / ${state.itemCount}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    IconButton(onClick = {
                        (activity as? PlayerActivity)?.enterPip()
                    }) {
                        Icon(Icons.Rounded.PictureInPictureAlt, "画中画", tint = Color.White)
                    }
                    IconButton(onClick = cycleOrientation) {
                        Icon(Icons.Rounded.ScreenRotation, "屏幕方向", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.SettingsIcon, "设置", tint = Color.White)
                    }
                }

                // 中央播放控制（贴近下方，便于拇指点击，同时避开底部进度栏）
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = viewModel::toggleShuffle) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            if (state.shuffleEnabled) "关闭随机播放" else "随机播放",
                            tint = if (state.shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = viewModel::playPrevious) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            "上一个",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = viewModel::togglePlay,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause
                            else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = viewModel::playNext) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            "下一个",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = viewModel::cycleRepeatMode) {
                        Icon(
                            imageVector = when (state.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                                else -> Icons.Rounded.Repeat
                            },
                            contentDescription = when (state.repeatMode) {
                                Player.REPEAT_MODE_ONE -> "单曲循环"
                                Player.REPEAT_MODE_ALL -> "列表循环"
                                else -> "不循环"
                            },
                            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                                Color.White.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // 底部栏
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xE6000000))
                            )
                        )
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val durationMs = state.durationMs.coerceAtLeast(0L)
                    val shownPosition = sliderValue
                        ?: state.positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatDuration(shownPosition.toLong()),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = shownPosition.coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                sliderValue?.let { viewModel.seekTo(it.toLong()) }
                                sliderValue = null
                            },
                            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = formatDuration(durationMs),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            locked = true
                            controlsVisible = false
                            lockHintVisible = true
                        }) {
                            Icon(Icons.Rounded.Lock, "锁定屏幕", tint = Color.White)
                        }
                        Box(
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    // 320dp 横向拖动约变化 1 倍速
                                    val pxPerX = 320.dp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        // 按住倍速按钮期间保持控制栏，避免长按判定还没完成就被自动隐藏
                                        speedDragging = true
                                        try {
                                            var moved = false
                                            var released = false
                                            // 先等长按超时；期间移动/抬手都算普通点击或拖动
                                            val ended = withTimeoutOrNull(
                                                viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                while (true) {
                                                    val e = awaitPointerEvent()
                                                    if (e.changes.count { it.pressed } >= 2) break
                                                    val c = e.changes.firstOrNull { it.id == down.id }
                                                        ?: break
                                                    if (!c.pressed) {
                                                        c.consume()
                                                        released = true
                                                        break
                                                    }
                                                    if ((c.position - down.position).getDistance() >
                                                        viewConfiguration.touchSlop
                                                    ) {
                                                        moved = true
                                                        break
                                                    }
                                                }
                                                true
                                            }
                                            if (ended == null) {
                                                // 长按倍速按钮：左右滑动调速，右滑加速、左滑减速
                                                val base = viewModel.state.value.speed
                                                gestureHint = GestureHint(
                                                    GestureType.INFO,
                                                    "${formatSpeed(base)} 倍速"
                                                )
                                                var accDx = 0f
                                                while (true) {
                                                    val e = awaitPointerEvent()
                                                    val c = e.changes.firstOrNull { it.id == down.id }
                                                        ?: break
                                                    accDx += c.position.x - c.previousPosition.x
                                                    val up = !c.pressed
                                                    c.consume()
                                                    if (up) break
                                                    val speed = (((base + accDx / pxPerX) * 20f)
                                                        .roundToInt() / 20f)
                                                        .coerceIn(0.5f, 3f)
                                                    if (speed != viewModel.state.value.speed) {
                                                        viewModel.setSpeed(speed)
                                                    }
                                                    gestureHint = GestureHint(
                                                        GestureType.INFO,
                                                        "${formatSpeed(speed)} 倍速"
                                                    )
                                                }
                                            } else if (!moved && released) {
                                                showSpeedSheet = true
                                            }
                                        } finally {
                                            // 无论控件是否被移除，松手/取消后都要复位
                                            speedDragging = false
                                            gestureHint = null
                                        }
                                    }
                                }
                                .sizeIn(minWidth = 48.dp, minHeight = 40.dp)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatSpeed(state.speed),
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(onClick = { showSubtitleSheet = true }) {
                            Icon(
                                Icons.Rounded.Subtitles,
                                "字幕",
                                tint = if (state.subtitleTracks.any { it.selected }) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                }
                            )
                        }
                        IconButton(onClick = { showAudioSheet = true }) {
                            Icon(Icons.Rounded.Audiotrack, "音轨", tint = Color.White)
                        }
                        IconButton(onClick = { showAspectSheet = true }) {
                            Icon(Icons.Rounded.AspectRatio, "画面比例", tint = Color.White)
                        }
                    }
                }
            }
        }

        // 锁屏状态：画面不可操作，解锁按钮短暂显示
        if (locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { lockHintVisible = true })
                    }
            )
            AnimatedVisibility(
                visible = lockHintVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            ) {
                IconButton(
                    onClick = {
                        locked = false
                        lockHintVisible = false
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Lock, "点击解锁", tint = Color.White)
                }
            }
        }

        // 错误提示
        state.error?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF0111A2B))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(error, color = Color.White, modifier = Modifier.padding(vertical = 12.dp))
                Row {
                    TextButton(onClick = viewModel::retry) { Text("重试") }
                    TextButton(onClick = {
                        viewModel.saveProgress()
                        onBack()
                    }) { Text("返回") }
                }
            }
        }
    }

    // 倍速选择
    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                SheetTitle("播放速度")
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                    SheetOption(
                        label = if (speed == 1f) "1x（正常）" else "${speed}x",
                        selected = state.speed == speed
                    ) {
                        viewModel.setSpeed(speed)
                        showSpeedSheet = false
                    }
                }
            }
        }
    }

    // 音轨选择
    if (showAudioSheet) {
        ModalBottomSheet(onDismissRequest = { showAudioSheet = false }) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                SheetTitle("音轨")
                if (state.audioTracks.isEmpty()) {
                    Text(
                        "暂无可用音轨",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.audioTracks.forEachIndexed { index, track ->
                        SheetOption(track.label, track.selected) {
                            viewModel.selectAudioTrack(index)
                            showAudioSheet = false
                        }
                    }
                }
            }
        }
    }

    // 字幕选择
    if (showSubtitleSheet) {
        ModalBottomSheet(onDismissRequest = { showSubtitleSheet = false }) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {
                SheetTitle("字幕")
                SheetOption(
                    label = "关闭字幕",
                    selected = state.subtitleTracks.none { it.selected }
                ) {
                    viewModel.selectSubtitle(-1)
                    showSubtitleSheet = false
                }
                state.subtitleTracks.forEachIndexed { index, track ->
                    SheetOption(track.label, track.selected) {
                        viewModel.selectSubtitle(index)
                        showSubtitleSheet = false
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SheetOption(label = "从文件加载字幕…", selected = false) {
                    showSubtitleSheet = false
                    subtitlePicker.launch(arrayOf("*/*"))
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SubtitleSizeOptions()
                SubtitleDelayOptions(
                    delayMs = state.subtitleDelayMs,
                    onDelayChange = viewModel::setSubtitleDelay
                )
            }
        }
    }

    // 画面比例
    if (showAspectSheet) {
        ModalBottomSheet(onDismissRequest = { showAspectSheet = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                SheetTitle("画面比例")
                SheetOption("适应屏幕", state.aspectMode == 0) {
                    viewModel.setAspectMode(0)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("裁剪填充", state.aspectMode == 1) {
                    viewModel.setAspectMode(1)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("拉伸填充", state.aspectMode == 2) {
                    viewModel.setAspectMode(2)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("16:9 宽屏", state.aspectMode == 3) {
                    viewModel.setAspectMode(3)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("4:3 经典", state.aspectMode == 4) {
                    viewModel.setAspectMode(4)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("3:2 相机", state.aspectMode == 5) {
                    viewModel.setAspectMode(5)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("1:1 方形", state.aspectMode == 6) {
                    viewModel.setAspectMode(6)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("9:16 竖屏", state.aspectMode == 7) {
                    viewModel.setAspectMode(7)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("21:9 超宽", state.aspectMode == 8) {
                    viewModel.setAspectMode(8)
                    zoom = 1f
                    showAspectSheet = false
                }
                SheetOption("自定义比例…", state.aspectMode == 9) {
                    showCustomRatioDialog = true
                }
                SheetOption("交换宽高（仅旋转画面）", surfaceRotation % 180 != 0) {
                    surfaceRotation = if (surfaceRotation % 180 == 0) 90 else 0
                    showAspectSheet = false
                }
                SheetOption("重置缩放", false) {
                    zoom = 1f
                    showAspectSheet = false
                }
            }
        }
    }

    // 自定义画面比例
    if (showCustomRatioDialog) {
        // 默认留空，由用户按需输入，不预填上次的比例
        var widthText by remember { mutableStateOf("") }
        var heightText by remember { mutableStateOf("") }
        val valid = widthText.toIntOrNull()?.let { it in 1..999 } == true &&
            heightText.toIntOrNull()?.let { it in 1..999 } == true
        AlertDialog(
            onDismissRequest = { showCustomRatioDialog = false },
            title = { Text("自定义画面比例") },
            text = {
                Column {
                    Text(
                        text = "输入视频画面的宽高比，例如 16 : 9",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filter(Char::isDigit).take(3) },
                            label = { Text("宽") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Text(":")
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter(Char::isDigit).take(3) },
                            label = { Text("高") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val w = widthText.toIntOrNull() ?: return@TextButton
                        val h = heightText.toIntOrNull() ?: return@TextButton
                        viewModel.setCustomAspect(w, h)
                        zoom = 1f
                        showCustomRatioDialog = false
                        showAspectSheet = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomRatioDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 只旋转视频画面（Surface），UI 方向保持不变；90/270 度时交换宽高 */
private fun Modifier.rotateSurface(degrees: Int): Modifier {
    if (degrees % 180 == 0) {
        return this.graphicsLayer { rotationZ = degrees.toFloat() }
    }
    return this
        .layout { measurable, constraints ->
            val swapped = Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
            val placeable = measurable.measure(swapped)
            layout(placeable.height, placeable.width) {
                placeable.place(
                    x = -(placeable.width - placeable.height) / 2,
                    y = -(placeable.height - placeable.width) / 2
                )
            }
        }
        .graphicsLayer { rotationZ = degrees.toFloat() }
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

/** 字幕字号，选择后立即生效并持久化 */
@Composable
private fun SubtitleSizeOptions() {
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text("字幕大小", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("小", "中", "大", "特大").forEachIndexed { index, label ->
                val selected = settings.subtitleSize == index
                Surface(
                    onClick = { SettingsRepository.setSubtitleSize(index) },
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 字幕时间点微调：正数让字幕更晚出现，负数更早 */
@Composable
private fun SubtitleDelayOptions(delayMs: Long, onDelayChange: (Long) -> Unit) {
    // 拖动时只更新滑块的临时值，松手后再应用（Media3 应用偏移需要重新定位解码）
    var sliderSeconds by remember(delayMs) { mutableFloatStateOf(delayMs / 1000f) }
    val previewMs = (sliderSeconds * 1000f).roundToInt().toLong()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "字幕延迟",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatSubtitleDelay(previewMs),
                style = MaterialTheme.typography.titleSmall
            )
            if (delayMs != 0L) {
                TextButton(onClick = { onDelayChange(0L) }) {
                    Text("重置")
                }
            }
        }
        Slider(
            value = sliderSeconds,
            onValueChange = { sliderSeconds = (it * 10f).roundToInt() / 10f },
            onValueChangeFinished = {
                onDelayChange((sliderSeconds * 1000f).roundToInt().toLong())
            },
            valueRange = -10f..10f
        )
        Text(
            text = "左右拖动调节，松手生效；字幕偏早选正数，偏晚选负数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatSubtitleDelay(delayMs: Long): String {
    val seconds = delayMs / 1000f
    return when {
        delayMs > 0 -> "+%.1fs".format(seconds)
        delayMs < 0 -> "%.1fs".format(seconds)
        else -> "0.0s"
    }
}

/** 固定画面比例：0 适应、1 裁剪、2 拉伸，3 起为具体比例，9 为自定义 */
private fun aspectRatioFor(mode: Int, customWidth: Int, customHeight: Int): Float? = when (mode) {
    3 -> 16f / 9f
    4 -> 4f / 3f
    5 -> 3f / 2f
    6 -> 1f
    7 -> 9f / 16f
    8 -> 21f / 9f
    9 -> customWidth.toFloat() / customHeight
    else -> null
}

@Composable
private fun SheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "外挂字幕"
} catch (_: Exception) {
    uri.lastPathSegment ?: "外挂字幕"
}
