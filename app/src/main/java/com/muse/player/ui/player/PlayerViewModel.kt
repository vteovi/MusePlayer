package com.muse.player.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.muse.player.data.AspectRepository
import com.muse.player.data.HistoryRepository
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SubtitleFinder
import com.muse.player.data.network.NetworkRepository
import com.muse.player.data.network.ServerType
import com.muse.player.playback.PlaybackService
import com.muse.player.playback.SubtitleOffset
import com.muse.player.playback.vlc.VlcEngine
import com.muse.player.playback.network.MuseDataSource
import com.muse.player.util.NetworkTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class TrackOption(
    val label: String,
    val selected: Boolean
)

data class PlayerUiState(
    val connected: Boolean = false,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val speed: Float = 1f,
    // 字幕时间偏移（毫秒），正数表示字幕延后出现
    val subtitleDelayMs: Long = 0L,
    val aspectMode: Int = 0,
    // 自定义比例模式（9）下该视频使用的宽高
    val customAspectWidth: Int = 0,
    val customAspectHeight: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesAvailable: Boolean = false,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    // 当前视频地址，播放页据此按视频分别保存缩放等画面状态
    val currentUri: String = "",
    // 播放器实例因解码兜底被重建时递增，界面据此重新绑定画面输出
    val decoderRestart: Int = 0,
    // 0 = Media3（系统解码器），1 = libVLC（内置 FFmpeg 软解）
    val engine: Int = 0,
    val error: String? = null
)

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    var player: MediaController? = null
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingStart: PendingStart? = null
    private var ticker: Job? = null
    private var startJob: Job? = null
    private var tickCount = 0
    private var entries: List<PlayEntry> = emptyList()

    // 已经按 HLS 重试过的地址，避免反复重试
    private val hlsRetriedUris = mutableSetOf<String>()
    private val softwareRetriedUris = mutableSetOf<String>()

    // 兼容引擎：Media3 解不出的格式交给 libVLC 软解。
    // 原生库体积很大，只有真的需要兜底时才初始化。
    private val vlcHolder = lazy {
        VlcEngine(getApplication<Application>()).also { engine -> observeVlc(engine) }
    }
    private val vlc: VlcEngine by vlcHolder
    private var usingVlc = false
    // 兼容引擎已应用的字幕字号，设置变化时重新打开当前媒体
    private var appliedSubtitleSize = SettingsRepository.state.value.subtitleSize

    private data class PendingStart(val entries: List<PlayEntry>, val index: Int, val positionMs: Long)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retryAsSoftware(error)) return
            if (retryAsHls(error)) return
            if (switchToVlcOnDecodeError(error)) return
            _state.update {
                it.copy(
                    error = errorMessage(error),
                    isBuffering = false
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }
        }
    }

    init {
        connect()
    }

    /** 兼容引擎自己维护播放进度，镜像到界面状态 */
    private fun observeVlc(engine: VlcEngine) {
        viewModelScope.launch {
            engine.state.collect { s ->
                if (!usingVlc) return@collect
                _state.update {
                    it.copy(
                        isPlaying = s.playing,
                        isBuffering = s.buffering,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                        error = s.error ?: it.error
                    )
                }
                if (s.ended) {
                    val next = _state.value.itemIndex + 1
                    if (next in entries.indices) playVlcIndex(next) else vlc.pause()
                }
            }
        }
    }

    private fun connect() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    player = controller
                    controller.addListener(listener)
                    _state.update { it.copy(connected = true) }
                    pendingStart?.let {
                        pendingStart = null
                        start(it.entries, it.index, it.positionMs)
                    }
                    startTicker()
                } catch (_: Throwable) {
                    _state.update { it.copy(error = "无法连接播放服务") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 设置播放队列并开始播放 */
    fun start(entries: List<PlayEntry>, index: Int, positionMs: Long) {
        val controller = player
        if (controller == null) {
            pendingStart = PendingStart(entries, index, positionMs)
            return
        }
        if (entries.isEmpty()) return
        this.entries = entries
        // 新播放任务从零开始，字幕偏移不跨视频沿用
        SubtitleOffset.offsetMs = 0L
        _state.update { it.copy(subtitleDelayMs = 0L) }
        val safeIndex = index.coerceIn(0, entries.lastIndex)
        val startPosition = positionMs.coerceAtLeast(0L)
        // 字幕文件查找涉及磁盘遍历，放到 IO 线程，避免拖慢起播
        startJob?.cancel()
        startJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                entries.mapIndexed { i, entry -> buildMediaItem(entry, probeMime = i == safeIndex) }
            }
            val current = player ?: return@launch
            current.setMediaItems(items, safeIndex, startPosition)
            current.prepare()
            current.playWhenReady = true
            _state.update { it.copy(error = null) }
        }
    }

    private suspend fun buildMediaItem(entry: PlayEntry, probeMime: Boolean = false): MediaItem {
        val metadata = MediaMetadata.Builder().setArtist("MusePlayer")
        // 网络视频的标题若只是占位（通用文件名或域名），不写死标题，
        // 让播放器读取流内的元数据（如 ID3 标题）来展示真实名称
        if (!(entry.isNetwork && NetworkTitles.isPlaceholder(entry.title))) {
            metadata.setTitle(entry.title)
        }
        val builder = MediaItem.Builder()
            .setUri(entry.uri)
            .setMediaMetadata(metadata.build())

        if (entry.isNetwork) {
            val mime = inferMimeType(entry.uri) ?: if (probeMime) probeMimeType(entry.uri) else null
            mime?.let { builder.setMimeType(it) }
        }

        val subtitlePaths = LinkedHashSet<String>()
        if (SettingsRepository.state.value.autoSubtitles) {
            if (entry.isNetwork) {
                // 远程视频：找同目录同名字幕，地址走同一套 muse+ 数据源加载
                subtitlePaths += SubtitleFinder.findForRemote(entry.uri)
            } else {
                subtitlePaths += SubtitleFinder.findFor(entry.path)
            }
        }
        subtitlePaths += entry.subtitleFiles

        val configs = subtitlePaths.mapNotNull { path ->
            if (path.startsWith("muse+")) remoteSubtitleConfig(path) else subtitleConfig(path)
        }
        if (configs.isNotEmpty()) {
            builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    private fun subtitleConfig(path: String): MediaItem.SubtitleConfiguration? {
        val file = File(path)
        if (!file.exists()) return null
        val mime = subtitleMimeType(file.name) ?: return null
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLabel(file.name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /** 远程字幕：不检查本地文件，地址由 MuseDataSource 解析 */
    private fun remoteSubtitleConfig(uri: String): MediaItem.SubtitleConfiguration? {
        val mime = subtitleMimeType(uri.substringBefore('?')) ?: return null
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
            .setMimeType(mime)
            .setLabel(uri.substringAfterLast('/').ifBlank { "字幕" })
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun subtitleMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun inferMimeType(url: String): String? {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            clean.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    /**
     * 地址没有可用后缀时（TVBox 等应用常给接口/代理地址），
     * 发一个 2KB 的探测请求，按 Content-Type 与文件头判断真实格式。
     */
    private fun probeMimeType(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", MuseDataSource.USER_AGENT)
                setRequestProperty("Range", "bytes=0-2047")
            }
            try {
                val contentType = connection.contentType?.lowercase().orEmpty()
                val head = ByteArray(2048)
                var length = 0
                connection.inputStream.use { input ->
                    while (length < head.size) {
                        val read = input.read(head, length, head.size - length)
                        if (read <= 0) break
                        length += read
                    }
                }
                detectMimeType(contentType, head, length)
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectMimeType(contentType: String, head: ByteArray, length: Int): String? {
        if (length <= 0) return null
        val text = String(head, 0, length, Charsets.ISO_8859_1)
        val isTs = length > 376 &&
            (head[0].toInt() and 0xFF) == 0x47 && (head[188].toInt() and 0xFF) == 0x47
        val isMatroska = length > 4 &&
            (head[0].toInt() and 0xFF) == 0x1A &&
            (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF &&
            (head[3].toInt() and 0xFF) == 0xA3
        return when {
            text.startsWith("#EXTM3U") || contentType.contains("mpegurl") ->
                MimeTypes.APPLICATION_M3U8
            contentType.contains("dash+xml") ||
                (text.trimStart().startsWith("<?xml") && text.contains("<MPD")) ->
                MimeTypes.APPLICATION_MPD
            isTs || contentType.contains("mp2t") -> MimeTypes.VIDEO_MP2T
            isMatroska -> MimeTypes.VIDEO_MATROSKA
            length > 8 && text.substring(4, 8) == "ftyp" -> MimeTypes.VIDEO_MP4
            contentType.contains("video/mp4") -> MimeTypes.VIDEO_MP4
            contentType.contains("video/webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    /** 解码失败（多见于设备不支持该编码）时，让服务改用软解重建播放器并重试一次 */
    private fun retryAsSoftware(error: PlaybackException): Boolean {
        val decodingFailed = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        if (!decodingFailed) return false
        val controller = player ?: return false
        val uri = controller.currentMediaItem?.localConfiguration?.uri?.toString() ?: return false
        if (!softwareRetriedUris.add(uri)) return false

        val command = SessionCommand(PlaybackService.ACTION_SOFTWARE_FALLBACK, Bundle.EMPTY)
        val result = controller.sendCustomCommand(command, Bundle.EMPTY)
        // 命令被会话拒绝时要让用户知道，避免界面停在“缓冲中”
        result.addListener(
            {
                val ok = runCatching {
                    result.get().resultCode == SessionResult.RESULT_SUCCESS
                }.getOrDefault(false)
                if (!ok) {
                    softwareRetriedUris.remove(uri)
                    _state.update {
                        it.copy(error = "切换软解失败，请重试", isBuffering = false)
                    }
                }
            },
            ContextCompat.getMainExecutor(getApplication())
        )
        // 服务会重建播放器实例，画面输出需要重新绑定
        _state.update {
            it.copy(error = null, isBuffering = true, decoderRestart = it.decoderRestart + 1)
        }
        return true
    }

    /** Media3 解不了（编码不支持、容器不支持）时切到 libVLC（内置 FFmpeg） */
    private fun switchToVlcOnDecodeError(error: PlaybackException): Boolean {
        // 轻量版不带 libVLC，不做兜底
        if (!VlcEngine.SUPPORTED) return false
        val unsupported = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> true

            else -> false
        }
        if (!unsupported || usingVlc) return false
        return switchToVlc()
    }

    /** 画面容器交给界面创建后挂载 */
    fun attachVlcView(view: org.videolan.libvlc.util.VLCVideoLayout) {
        vlc.attachView(view)
    }

    private fun switchToVlc(): Boolean {
        val controller = player ?: return false
        val index = controller.currentMediaItemIndex
        val entry = entries.getOrNull(index) ?: return false
        val url = vlcUrl(entry) ?: return false
        val position = controller.currentPosition.coerceAtLeast(0L)
        val speed = controller.playbackParameters.speed

        controller.pause()
        usingVlc = true
        vlc.play(url, position, speed, SettingsRepository.state.value.subtitleSize, _state.value.subtitleDelayMs)
        loadVlcSubtitles(entry)
        _state.update {
            it.copy(
                engine = 1,
                error = null,
                isPlaying = false,
                isBuffering = true,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                subtitlesAvailable = false,
                itemIndex = index,
                itemCount = entries.size,
                hasPrev = index > 0,
                hasNext = index < entries.lastIndex,
                title = entry.title,
                currentUri = entry.uri
            )
        }
        restoreAspect(entry.uri)
        return true
    }

    /** 兼容引擎模式下按队列索引播放 */
    private fun playVlcIndex(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        val url = vlcUrl(entry) ?: return
        vlc.play(url, 0L, _state.value.speed, SettingsRepository.state.value.subtitleSize, _state.value.subtitleDelayMs)
        loadVlcSubtitles(entry)
        _state.update {
            it.copy(
                itemIndex = index,
                title = entry.title,
                isPlaying = false,
                isBuffering = true,
                hasPrev = index > 0,
                hasNext = index < entries.lastIndex,
                currentUri = entry.uri,
                error = null
            )
        }
        restoreAspect(entry.uri)
    }

    /** 兼容引擎不读 Media3 的字幕配置，改为播放后手动挂载同名字幕 */
    private fun loadVlcSubtitles(entry: PlayEntry) {
        if (!SettingsRepository.state.value.autoSubtitles) return
        val uri = entry.uri
        viewModelScope.launch {
            val urls = withContext(Dispatchers.IO) {
                val paths = LinkedHashSet<String>()
                if (entry.isNetwork) {
                    paths += SubtitleFinder.findForRemote(uri)
                } else {
                    paths += SubtitleFinder.findFor(entry.path)
                    paths += entry.subtitleFiles
                }
                paths.mapNotNull { path ->
                    runCatching { vlcSubtitleUrl(path) }.getOrNull()
                }
            }
            // 期间可能已经切到别的视频，避免给新视频挂错字幕
            if (!usingVlc || _state.value.currentUri != uri) return@launch
            urls.forEach { vlc.addSubtitle(it) }
        }
    }

    /** 字幕地址转换：本地转 file://，远程复用带凭据的协议地址 */
    private fun vlcSubtitleUrl(path: String): String? = when {
        path.startsWith("muse+") -> vlcUrl(path, null)
        path.startsWith("content://") -> path
        else -> File(path).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
    }

    /** 字幕字号变化时让兼容引擎重新打开当前媒体（libVLC 只在新媒体打开时读取字号） */
    fun applySubtitleSize() {
        val size = SettingsRepository.state.value.subtitleSize
        if (size == appliedSubtitleSize) return
        appliedSubtitleSize = size
        if (!usingVlc) return
        val entry = entries.getOrNull(_state.value.itemIndex) ?: return
        val url = vlcUrl(entry) ?: return
        vlc.play(url, _state.value.positionMs, _state.value.speed, size, _state.value.subtitleDelayMs)
        applyVlcAspect(_state.value.aspectMode)
        loadVlcSubtitles(entry)
    }

    /** 内部协议地址转成 libVLC 可直接打开的地址（SMB/FTP 需要把凭据放进 URL） */
    private fun vlcUrl(entry: PlayEntry): String? = vlcUrl(entry.uri, entry.path)

    private fun vlcUrl(raw: String, localPath: String?): String? {
        // MediaStore 的 content:// 交给 libVLC 不稳定，本地文件优先用真实路径
        if (raw.startsWith("content://")) {
            if (localPath != null && File(localPath).exists()) {
                return Uri.fromFile(File(localPath)).toString()
            }
            return raw
        }
        val resolved = NetworkRepository.resolve(Uri.parse(raw)) ?: return raw
        val (server, path) = resolved
        val auth = if (server.username.isBlank()) {
            ""
        } else {
            Uri.encode(server.username) + ":" + Uri.encode(server.password) + "@"
        }
        return when (server.type) {
            ServerType.SMB -> buildString {
                append("smb://").append(auth).append(server.host)
                if (server.port != ServerType.SMB.defaultPort) append(':').append(server.port)
                NetworkRepository.segments(path).forEach { append('/').append(it) }
            }

            ServerType.FTP -> buildString {
                append("ftp://").append(auth).append(server.host).append(':').append(server.port)
                NetworkRepository.segments(path).forEach { append('/').append(it) }
            }

            ServerType.WEBDAV -> {
                // libVLC 通过 HTTP 访问 WebDAV，凭据需要写进地址
                val base = NetworkRepository.webDavUrl(server, path, directory = false)
                if (auth.isBlank()) base else base.replaceFirst("://", "://$auth")
            }
        }
    }

    /** 固定画面比例交给 VLC 处理；其余按原始比例显示 */
    private fun applyVlcAspect(mode: Int) {
        if (!usingVlc) return
        val ratio = when (mode) {
            3 -> "16:9"
            4 -> "4:3"
            5 -> "3:2"
            6 -> "1:1"
            7 -> "9:16"
            8 -> "21:9"
            9 -> {
                val w = _state.value.customAspectWidth
                val h = _state.value.customAspectHeight
                if (w > 0 && h > 0) "$w:$h" else null
            }

            else -> null
        }
        vlc.setAspectRatio(ratio)
    }

    /** 解码类错误：能识别出 10bit H.265 时给出明确原因，否则附带编码信息 */
    private fun errorMessage(error: PlaybackException): String {
        val base = friendlyError(error)
        val decoding = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        if (!decoding) return base
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val softTried = uri != null && uri in softwareRetriedUris
        val videoCodecs = videoCodecStrings()
        // profile_idc 为 2 即 HEVC Main10（10bit），老/低端设备的硬解器往往只支持 8bit
        if (videoCodecs.any { hevcProfileIdc(it) == 2 }) {
            return buildString {
                append("播放失败：该视频是 10bit H.265（Main10）")
                append(if (softTried) "，软解也无法解码" else "，当前设备不支持此编码")
                append("；请换用 8bit 版本或支持该格式的播放器")
            }
        }
        val codecs = codecSummary()
        return buildString {
            append(base)
            if (softTried) append("（已尝试软解）")
            if (codecs.isNotEmpty()) append("　编码：").append(codecs)
        }
    }

    /** 当前视频轨的 codecs 字符串，例如 hvc1.2.4.L120.90 */
    private fun videoCodecStrings(): List<String> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            .mapNotNull { it.codecs?.takeIf(String::isNotBlank) }
    }

    /** 解析 HEVC codecs 字符串中的 profile_idc，2 表示 Main10（10bit） */
    private fun hevcProfileIdc(codecs: String): Int? {
        val parts = codecs.split('.')
        if (parts.size < 2) return null
        if (parts[0] != "hvc1" && parts[0] != "hev1") return null
        return parts[1].toIntOrNull()
    }

    /** 当前视频/音频轨的编码信息，例如 视频 video/hevc hvc1.2.4 */
    private fun codecSummary(): String {
        val p = player ?: return ""
        val parts = mutableListOf<String>()
        p.currentTracks.groups.forEach { group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_VIDEO -> "视频"
                C.TRACK_TYPE_AUDIO -> "音频"
                else -> return@forEach
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                val codec = format.codecs?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                parts += "$kind $mime$codec"
            }
        }
        return parts.distinct().joinToString("，")
    }

    /** 无法识别容器且地址是网络地址时，按 HLS 重新构建一次并重试（每个地址只尝试一次） */
    private fun retryAsHls(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        val controller = player ?: return false
        val item = controller.currentMediaItem ?: return false
        val uri = item.localConfiguration?.uri?.toString() ?: return false
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return false
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) return false
        if (!hlsRetriedUris.add(uri)) return false

        val index = controller.currentMediaItemIndex
        val position = controller.currentPosition
        val items = (0 until controller.mediaItemCount)
            .map { controller.getMediaItemAt(it) }
            .toMutableList()
        if (index !in items.indices) return false
        items[index] = item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
        controller.setMediaItems(items, index, position)
        controller.prepare()
        controller.playWhenReady = true
        _state.update { it.copy(error = null, isBuffering = true) }
        return true
    }

    /** 把错误码翻译成可读原因，网络类错误附带底层异常信息，便于判断是源站问题还是链接失效 */
    private fun friendlyError(error: PlaybackException): String {
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络请求失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接到服务器"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接服务器超时"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "服务器返回的内容不是视频"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误状态码"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或地址已失效"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有访问权限"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "系统禁止播放明文 HTTP 地址"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频文件损坏或格式异常"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "无法识别视频格式（该地址可能不是直链视频）"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "播放列表格式异常"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "不支持该播放列表格式"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频解码失败，设备可能不支持该编码格式"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "无法初始化视频解码器"
            else -> error.errorCodeName
        }
        val detail = if (error.errorCode in 2000..2999) {
            generateSequence(error.cause) { it.cause }
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
        } else {
            null
        }
        return if (detail == null) "播放失败：$reason" else "播放失败：$reason（${detail.take(80)}）"
    }

    private fun refresh(player: Player) {
        // 兼容引擎接管后，Media3 的状态不再代表真实播放进度
        if (usingVlc) return
        val audioTracks = mutableListOf<TrackOption>()
        val subtitleTracks = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        audioTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, audioTracks.size, true),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        subtitleTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, subtitleTracks.size, false),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }
            }
        }

        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        // 优先展示流内元数据里的真实标题，占位标题时不覆盖
        val runtimeTitle = player.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
        val itemTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?.takeIf { it.isNotBlank() }

        val newUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: _state.value.currentUri
        val previousUri = _state.value.currentUri
        _state.update {
            it.copy(
                connected = true,
                title = runtimeTitle
                    ?: itemTitle
                    ?: entries.getOrNull(player.currentMediaItemIndex)?.title
                    ?: "",
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                hasPrev = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                speed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                subtitlesAvailable = !textDisabled && subtitleTracks.any { it.selected },
                itemIndex = player.currentMediaItemIndex,
                itemCount = player.mediaItemCount,
                currentUri = newUri
            )
        }
        // 切到另一个视频时恢复它上次使用的画面比例（VLC 模式由 playVlcIndex 负责）
        if (!usingVlc && newUri != previousUri && newUri.isNotBlank()) restoreAspect(newUri)
    }

    private fun trackLabel(
        label: String?,
        language: String?,
        codecs: String?,
        index: Int,
        isAudio: Boolean
    ): String {
        val base = label
            ?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }?.uppercase()
            ?: "${if (isAudio) "音轨" else "字幕"} ${index + 1}"
        return if (codecs.isNullOrBlank()) base else "$base · $codecs"
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                if (usingVlc) {
                    // 兼容引擎的进度由 VlcEngine 事件驱动，这里只负责定期存档
                    tickCount++
                    if (tickCount >= 10) {
                        tickCount = 0
                        if (_state.value.isPlaying) saveCurrentProgress()
                    }
                    delay(500)
                    continue
                }
                val controller = player
                if (controller != null) {
                    val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    _state.update {
                        it.copy(
                            positionMs = controller.currentPosition.coerceAtLeast(0),
                            durationMs = duration,
                            isPlaying = controller.isPlaying,
                            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                            hasPrev = controller.hasPreviousMediaItem(),
                            hasNext = controller.hasNextMediaItem()
                        )
                    }
                    tickCount++
                    if (tickCount >= 10) {
                        tickCount = 0
                        if (controller.isPlaying) saveCurrentProgress()
                    }
                }
                delay(500)
            }
        }
    }

    fun togglePlay() {
        if (usingVlc) {
            if (_state.value.isPlaying) {
                vlc.pause()
                saveCurrentProgress()
            } else {
                val s = _state.value
                if (s.durationMs > 0 && s.positionMs >= s.durationMs - 500) vlc.seekTo(0)
                vlc.resume()
            }
            return
        }
        val controller = player ?: return
        if (controller.isPlaying) {
            controller.pause()
            saveCurrentProgress()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0)
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (usingVlc) {
            val duration = _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            vlc.seekTo(positionMs.coerceIn(0L, duration))
            return
        }
        val controller = player ?: return
        val target = positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) {
        if (usingVlc) {
            val duration = _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            vlc.seekTo((_state.value.positionMs + deltaMs).coerceIn(0L, duration))
            return
        }
        val controller = player ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, duration)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun playNext() {
        if (usingVlc) {
            val next = _state.value.itemIndex + 1
            if (next in entries.indices) playVlcIndex(next)
            return
        }
        val controller = player ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        if (usingVlc) {
            val current = _state.value.itemIndex
            if (_state.value.positionMs > 5_000) {
                vlc.seekTo(0)
            } else if (current - 1 in entries.indices) {
                playVlcIndex(current - 1)
            } else {
                vlc.seekTo(0)
            }
            return
        }
        val controller = player ?: return
        if (controller.currentPosition > 5_000) {
            controller.seekTo(0)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else {
            controller.seekTo(0)
        }
    }

    fun setSpeed(speed: Float) {
        if (usingVlc) {
            vlc.setRate(speed)
            _state.update { it.copy(speed = speed) }
            return
        }
        player?.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** 手动调节字幕时间点：正数让字幕更晚出现，负数更早 */
    fun setSubtitleDelay(delayMs: Long) {
        val ms = delayMs.coerceIn(-10_000L, 10_000L)
        if (ms == _state.value.subtitleDelayMs) return
        SubtitleOffset.offsetMs = ms
        _state.update { it.copy(subtitleDelayMs = ms) }
        if (usingVlc) {
            vlc.setSubtitleDelay(ms)
            return
        }
        // Media3 需要重新解码当前字幕，微调 1ms 再定位，确保一定会触发重新解码
        player?.let { controller ->
            controller.seekTo((controller.currentPosition + 1).coerceAtLeast(0L))
        }
    }

    /** 循环模式：关闭 → 列表循环 → 单曲循环 → 关闭 */
    fun cycleRepeatMode() {
        val controller = player ?: return
        val next = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = next
        SettingsRepository.setRepeatMode(next)
        _state.update { it.copy(repeatMode = next) }
    }

    /** 切换随机播放 */
    fun toggleShuffle() {
        val controller = player ?: return
        val enabled = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = enabled
        SettingsRepository.setShuffleEnabled(enabled)
        _state.update { it.copy(shuffleEnabled = enabled) }
    }

    fun setAspectMode(mode: Int) {
        _state.update { it.copy(aspectMode = mode) }
        persistAspect()
        applyVlcAspect(mode)
    }

    /** 自定义比例：先记录宽高，再切换到自定义模式并保存到该视频 */
    fun setCustomAspect(width: Int, height: Int) {
        _state.update {
            it.copy(
                customAspectWidth = width.coerceIn(1, 999),
                customAspectHeight = height.coerceIn(1, 999)
            )
        }
        setAspectMode(9)
    }

    /** 把当前比例记到当前视频名下 */
    private fun persistAspect() {
        val s = _state.value
        AspectRepository.put(
            s.currentUri,
            AspectRepository.Aspect(s.aspectMode, s.customAspectWidth, s.customAspectHeight)
        )
    }

    /** 切换到新视频时恢复该视频上次使用的比例 */
    private fun restoreAspect(uri: String) {
        val saved = AspectRepository.get(uri)
        _state.update {
            it.copy(
                aspectMode = saved?.mode ?: 0,
                customAspectWidth = saved?.width ?: 0,
                customAspectHeight = saved?.height ?: 0
            )
        }
        applyVlcAspect(saved?.mode ?: 0)
    }

    fun selectAudioTrack(index: Int) {
        val controller = player ?: return
        val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(index) ?: return
        controller.trackSelectionParameters = controller.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun selectSubtitle(index: Int) {
        val controller = player ?: return
        val builder = controller.trackSelectionParameters.buildUpon()
        if (index < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = groups.getOrNull(index) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        controller.trackSelectionParameters = builder.build()
    }

    /** 加载用户选择的外挂字幕，保持当前进度 */
    fun addSubtitle(uri: Uri, displayName: String) {
        val controller = player ?: return
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount) return
        val current = controller.getMediaItemAt(index)
        val mime = when (displayName.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(displayName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val local = current.localConfiguration ?: return
        val updatedItem = MediaItem.Builder()
            .setUri(local.uri)
            .setMimeType(local.mimeType)
            .setMediaMetadata(current.mediaMetadata)
            .setSubtitleConfigurations(local.subtitleConfigurations + config)
            .build()
        val items = (0 until controller.mediaItemCount)
            .map { controller.getMediaItemAt(it) }
            .toMutableList()
        if (index !in items.indices) return
        items[index] = updatedItem
        val position = controller.currentPosition
        val wasPlaying = controller.isPlaying
        controller.setMediaItems(items, index, position)
        controller.prepare()
        if (wasPlaying) controller.play()
    }

    fun retry() {
        if (usingVlc) {
            val entry = entries.getOrNull(_state.value.itemIndex) ?: return
            val url = vlcUrl(entry) ?: return
            _state.update { it.copy(error = null, isBuffering = true) }
            vlc.play(url, _state.value.positionMs, _state.value.speed, SettingsRepository.state.value.subtitleSize, _state.value.subtitleDelayMs)
            loadVlcSubtitles(entry)
            return
        }
        val controller = player ?: return
        _state.update { it.copy(error = null) }
        controller.prepare()
        controller.play()
    }

    fun pauseAndSave() {
        if (usingVlc) vlc.pause() else player?.pause()
        saveCurrentProgress()
    }

    /** 恢复播放（从小窗回到全屏时使用） */
    fun resume() {
        if (usingVlc) vlc.resume() else player?.play()
    }

    /** 是否处于播放状态：兼容引擎下 Media3 是暂停的，不能只看 playWhenReady */
    fun isActive(): Boolean = if (usingVlc) _state.value.isPlaying else player?.playWhenReady == true

    fun saveProgress() {
        saveCurrentProgress()
    }

    private fun saveCurrentProgress() {
        if (usingVlc) {
            val index = _state.value.itemIndex
            val entry = entries.getOrNull(index) ?: return
            val position = _state.value.positionMs
            val duration = _state.value.durationMs.takeIf { it > 0 } ?: entry.durationMs
            if (position > 0) {
                HistoryRepository.record(entry.uri, entry.title, position, duration)
            }
            return
        }
        val controller = player ?: return
        val item = controller.currentMediaItem ?: return
        val index = controller.currentMediaItemIndex
        val entry = entries.getOrNull(index)
        // 网络 / 局域网视频同样写入历史记录，方便下次继续播放
        val uri = item.localConfiguration?.uri?.toString()
            ?: entry?.uri
            ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: entry?.durationMs
            ?: 0L
        val position = controller.currentPosition
        // 优先使用流内元数据标题；旧历史里的 index 之类占位标题会在重新播放时被替换
        val title = controller.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
            ?: item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: entry?.title?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
            ?: entry?.uri?.takeIf { it.startsWith("http") }?.let { NetworkTitles.of(it) }
            ?: entry?.title
            ?: ""
        if (position > 0) {
            HistoryRepository.record(uri, title, position, duration)
        }
    }

    override fun onCleared() {
        saveCurrentProgress()
        ticker?.cancel()
        ticker = null
        player?.removeListener(listener)
        player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        vlcHolder.let { if (it.isInitialized()) it.value.release() }
        super.onCleared()
    }
}
