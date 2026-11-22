package com.muse.player.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.playback.PlaybackService
import com.muse.player.ui.about.AboutScreen
import com.muse.player.ui.settings.SettingsScreen
import com.muse.player.ui.theme.MuseTheme
import com.muse.player.util.NetworkTitles

class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    // 是否刚退出画中画：回全屏时会在 onResume 清掉，只有界面被结束才保留
    private var pipExited = false
    // 是否因为「疑似关闭小窗」而暂停过：回全屏时据此恢复播放，避免误暂停
    private var pausedForPipExit = false
    private val handler = Handler(Looper.getMainLooper())
    // 退出小窗后延迟确认是否回到前台，没回来才按「关闭小窗」处理
    private val pipPauseCheck = Runnable {
        // 又回到小窗了，不按关闭处理
        if (isInPictureInPictureMode) return@Runnable
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (viewModel.isActive()) pausedForPipExit = true
            viewModel.pauseAndSave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放器始终是深色背景，强制状态栏/导航栏使用浅色图标，避免系统跟随日间模式变黑看不清
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 关闭系统给手势条自动加的浅色描边背景
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // 先连接播放服务并提交播放请求，与 Compose 初始化并行执行，缩短起播等待
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            MuseTheme(darkTheme = true) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                // 0 播放页, 1 设置, 2 关于。设置从播放页覆盖打开，播放页保持存活
                var overlay by rememberSaveable { mutableIntStateOf(0) }
                Box(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(
                        viewModel = viewModel,
                        state = state,
                        // 注意：不能再调用 onBackPressedDispatcher.onBackPressed()，
                        // 否则会与 PlayerScreen 内的 BackHandler 形成无限递归
                        onBack = {
                            viewModel.pauseAndSave()
                            finish()
                        },
                        onOpenSettings = { overlay = 1 },
                        backHandlerEnabled = overlay == 0
                    )
                    when (overlay) {
                        1 -> SettingsScreen(
                            onBack = { overlay = 0 },
                            onOpenAbout = { overlay = 2 }
                        )

                        2 -> AboutScreen(onBack = { overlay = 1 })
                    }
                    BackHandler(enabled = overlay != 0) {
                        overlay = if (overlay == 2) 1 else 0
                    }
                }
            }
        }

        // 返回逻辑统一交给 PlayerScreen 内的 BackHandler 处理（暂停 + finish）。
        // 这里不再额外注册 Activity 级返回回调，避免两个回调互相抢占导致需要多次返回才退出。
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val entries = intent.getParcelableArrayListExtra<PlayEntry>(EXTRA_ENTRIES)
        if (!entries.isNullOrEmpty()) {
            viewModel.start(
                entries = entries,
                index = intent.getIntExtra(EXTRA_INDEX, 0),
                positionMs = intent.getLongExtra(EXTRA_POSITION, 0L)
            )
            return
        }

        // 其他应用通过 muse://play?url=... 调起
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && data.scheme == SCHEME_MUSE) {
            val url = data.getQueryParameter("url") ?: data.getQueryParameter("src")
            if (!url.isNullOrBlank()) {
                val title = data.getQueryParameter("title") ?: NetworkTitles.of(url)
                viewModel.start(
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
            }
            return
        }

        // 其他应用通过“分享”或“打开方式”传入的视频
        val streamUris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ) ?: emptyList()

            else -> emptyList()
        }
        val sharedUris = (streamUris + clipDataUris(intent)).distinctBy { it.toString() }
        if (sharedUris.isNotEmpty()) {
            viewModel.start(buildEntries(sharedUris, intentTitles(intent)), 0, 0L)
            return
        }

        // 其他应用分享文本中的网络流媒体链接（如 m3u8 地址）
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            val url = extractMediaUrl(text)
            if (url != null) {
                // 优先使用调用方给的标题，其次是分享文本里链接前的视频名称
                val title = intentTitles(intent).firstOrNull()
                    ?: titleFromShareText(text, url)
                    ?: NetworkTitles.of(url)
                viewModel.start(
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
            } else {
                Toast.makeText(this, "未找到可播放的视频链接", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        if (intent.action == Intent.ACTION_VIEW && data != null) {
            viewModel.start(buildEntries(listOf(data), intentTitles(intent)), 0, 0L)
        }
    }

    private fun clipDataUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun buildEntries(
        uris: List<Uri>,
        extraTitles: List<String?> = emptyList()
    ): List<PlayEntry> =
        uris.mapIndexed { index, uri ->
            val network = uri.scheme == "http" || uri.scheme == "https"
            val raw = queryDisplayName(uri) ?: uri.lastPathSegment ?: "视频"
            val localTitle = raw.substringBeforeLast('.').ifBlank { raw }
            PlayEntry(
                uri = uri.toString(),
                title = if (network) {
                    extraTitles.getOrNull(index)?.takeIf { it.isNotBlank() }
                        ?: NetworkTitles.of(uri.toString())
                } else {
                    localTitle
                },
                isNetwork = network
            )
        }

    /** 从分享文本中提取网络地址：优先视频扩展名，否则取第一个 http(s) 链接 */
    private fun extractMediaUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val candidates = URL_REGEX.findAll(text)
            .map { it.value.trimEnd('。', '，', ',', '.', ')', '）', '!', '！', ';', '；') }
            .toList()
        return candidates.firstOrNull { url ->
            val path = url.substringBefore('?').lowercase()
            MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
        } ?: candidates.firstOrNull()
    }

    /**
     * 收集调用方传入的视频标题。
     *
     * 除了系统标准的 EXTRA_TITLE / EXTRA_SUBJECT，还兼容 MX Player 的 Intent 约定：
     * 不少流媒体应用按 MX 的规范把标题放在 "title" 或 "video_list.name" 里，
     * 这也是 MX 几乎总能显示名称的原因。
     */
    private fun intentTitles(intent: Intent): List<String?> {
        val listNames = stringListExtra(intent, "video_list.name")
        val mxTitles = stringListExtra(intent, "title")
        // MX 文档：同时存在时以 video_list.name 为准
        val names = if (listNames.isNotEmpty()) listNames else mxTitles
        val single = sequenceOf(
            names.firstOrNull(),
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.clipData?.description?.label?.toString()
        ).mapNotNull { it?.trim() }
            .firstOrNull { isValidTitle(it) }

        if (names.size > 1) {
            return names.mapIndexed { index, name ->
                name?.trim()?.takeIf { isValidTitle(it) } ?: if (index == 0) single else null
            }
        }
        return listOf(single)
    }

    /** 读取 String / String[] / List 形式的 extra，统一成列表 */
    private fun stringListExtra(intent: Intent, key: String): List<String?> =
        when (val value = intent.extras?.get(key)) {
            is Array<*> -> value.map { it?.toString() }
            is List<*> -> value.map { it?.toString() }
            is String -> listOf(value)
            is CharSequence -> listOf(value.toString())
            else -> emptyList()
        }

    private fun isValidTitle(title: String): Boolean =
        title.length in 2..120 && !title.contains('\n') && !NetworkTitles.isPlaceholder(title)

    /** 分享文本常在链接前带上视频名称，例如“《庆余年》第 1 集 https://.../index.m3u8” */
    private fun titleFromShareText(text: String?, url: String): String? {
        if (text.isNullOrBlank()) return null
        val index = text.indexOf(url)
        if (index <= 0) return null
        val title = text.substring(0, index)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(我正在看|我在看|正在播放|分享视频|分享|推荐)[:：]?\\s*"), "")
            .trim()
            .trim('“', '”', '"', '「', '」', '【', '】', '《', '》', '：', ':', '-', '—', '|', '·', ' ')
        return title.takeIf { it.length in 2..80 }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = viewModel.state.value
        val ratio = if (size.videoWidth > 0 && size.videoHeight > 0) {
            Rational(size.videoWidth, size.videoHeight)
        } else {
            Rational(16, 9)
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .build()
        try {
            if (enterPictureInPictureMode(params)) {
                // 小窗背后应该是桌面，否则会停留在本应用的媒体库界面
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(home)
            }
        } catch (_: Exception) {
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipExited = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // 重新进入小窗：撤掉上一次退出小窗留下的检查，避免在小窗里被误暂停
            handler.removeCallbacks(pipPauseCheck)
        } else {
            // 「关闭小窗」和「恢复全屏」都会走到这里：恢复全屏会紧接着 onResume，
            // 关闭小窗则不会。稍后检查一次前台状态来区分，避免依赖 isFinishing。
            schedulePipPauseCheck()
        }
    }

    private fun schedulePipPauseCheck() {
        handler.removeCallbacks(pipPauseCheck)
        handler.postDelayed(pipPauseCheck, 800)
    }

    override fun onResume() {
        super.onResume()
        // 从小窗恢复成全屏时继续播放
        pipExited = false
        handler.removeCallbacks(pipPauseCheck)
        // 过渡期间若已被暂停（回调顺序在不同机型上不一致），这里恢复播放
        if (pausedForPipExit) {
            pausedForPipExit = false
            viewModel.resume()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pipPauseCheck)
        // 小窗被关闭时界面会被销毁，但播放由 MediaSessionService 承载，不会自动停。
        // 后台播放关闭时直接结束播放服务，确保界面消失后不再出声。
        if (!isChangingConfigurations && !SettingsRepository.state.value.backgroundPlay) {
            viewModel.pauseAndSave()
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) return
        // 正在从小窗恢复全屏：交给 onResume 与延迟检查判定，避免切换过程中被暂停
        if (pipExited) return
        if (!SettingsRepository.state.value.backgroundPlay) {
            // 在 onPause 就暂停，比等到 onStop 更早，避免退出时还有一两秒声音
            viewModel.pauseAndSave()
        }
    }

    override fun onStop() {
        super.onStop()
        // 小窗被关闭时 Activity 会直接结束（isFinishing 不一定为 true），
        // 此时播放服务仍在运行，必须主动暂停，否则关闭小窗后还能听到后台声音
        if (isFinishing) {
            viewModel.pauseAndSave()
            return
        }
        if (isInPictureInPictureMode) {
            // 小窗正常悬浮时 Activity 只是暂停、不会走 onStop；
            // 走到这里说明小窗已被关闭或隐藏，立即暂停避免残留在后台出声
            if (!SettingsRepository.state.value.backgroundPlay) {
                // 部分机型在「恢复全屏」的过渡里也会短暂处于画中画状态，
                // 标记一下，onResume 时如果确实回到全屏就恢复播放
                pausedForPipExit = viewModel.isActive()
                viewModel.pauseAndSave()
            }
            return
        }
        if (pipExited) {
            // 刚退出小窗：恢复全屏会紧接着 onResume（取消这次检查），
            // 关闭小窗则界面会销毁（onDestroy 停止服务）。这里立即暂停会让
            // 从小窗恢复全屏时先被暂停一下，所以改为延迟确认
            schedulePipPauseCheck()
            return
        }
        if (SettingsRepository.state.value.backgroundPlay) {
            viewModel.saveProgress()
        } else {
            viewModel.pauseAndSave()
        }
    }

    companion object {
        const val EXTRA_ENTRIES = "extra_entries"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_POSITION = "extra_position"
        private const val SCHEME_MUSE = "muse"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+")
        private val MEDIA_EXTENSIONS = listOf(
            "m3u8", "m3u", "mpd", "mp4", "mkv", "webm", "flv", "ts", "mov", "avi", "m4v", "3gp"
        )
    }
}
