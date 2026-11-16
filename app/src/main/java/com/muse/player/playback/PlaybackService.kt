package com.muse.player.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.muse.player.data.HistoryRepository
import com.muse.player.data.SettingsRepository
import com.muse.player.playback.network.MuseDataSource
import com.muse.player.playback.network.SchemeRoutingDataSourceFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 后台播放服务：MediaSessionService + ExoPlayer，
 * 支持通知栏控制、后台播放、画中画切换、硬件/软件解码切换。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var appliedDecoderMode = SettingsRepository.state.value.decoderMode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 网络视频磁盘缓存：看过的段落再次拖动时直接读本地，不再重新下载
    private var mediaCache: SimpleCache? = null
    private var cacheDatabase: StandaloneDatabaseProvider? = null

    private fun obtainCache(): SimpleCache {
        mediaCache?.let { return it }
        val database = StandaloneDatabaseProvider(this)
        val cache = SimpleCache(
            File(cacheDir, "media_cache"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            database
        )
        cacheDatabase = database
        mediaCache = cache
        return cache
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        // 解码模式变化时重建播放器，保留当前播放队列与进度
        scope.launch {
            SettingsRepository.state
                .map { it.decoderMode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode != appliedDecoderMode) {
                        appliedDecoderMode = mode
                        rebuildPlayer()
                    }
                }
        }

        // 仅为预热的场景：一段时间内没有任何播放内容就释放服务
        scope.launch {
            delay(30_000)
            val current = player
            if (current == null || current.mediaItemCount == 0) stopSelf()
        }

        // 通知栏播放卡片开关：关闭时移除已有通知，开启时重新创建
        scope.launch {
            SettingsRepository.state
                .map { it.showMediaNotification }
                .distinctUntilChanged()
                .collect { show ->
                    val session = mediaSession ?: return@collect
                    if (show) {
                        val p = player
                        onUpdateNotification(
                            session,
                            p?.playWhenReady == true && (p?.mediaItemCount ?: 0) > 0
                        )
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
        }
    }

    /**
     * 通知栏播放卡片默认关闭：关闭时不创建通知、也不进入前台服务。
     * 开启时沿用 Media3 默认的媒体通知。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!SettingsRepository.state.value.showMediaNotification) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun buildPlayer(): ExoPlayer {
        val upstream = MuseDataSource.Factory(this)
        val cached = CacheDataSource.Factory()
            .setCache(obtainCache())
            .setUpstreamDataSourceFactory(upstream)
            // 缓存读写异常时自动回退到直连，避免缓存损坏导致无法播放
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ExoPlayer.Builder(this)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(SchemeRoutingDataSourceFactory(upstream, cached))
        )
        .setRenderersFactory(buildRenderersFactory())
        // 调整缓冲策略：局域网拖动后尽快恢复播放，并保留最近 15 秒已下载数据
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    20_000, // 最小缓冲：始终保持约 20 秒已下载数据，抵消局域网波动
                    60_000, // 最大缓冲
                    1_000, // 起播所需缓冲
                    2_500 // 卡顿后重新起播所需缓冲，避免刚恢复又立刻卡住
                )
                .setBackBuffer(20_000, true)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .apply {
            // 记录实际初始化的视频解码器，播放界面据此显示硬解 / 软解
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    DecoderStatus.update(decoderName)
                }
            })
        }
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val mode = SettingsRepository.state.value.decoderMode
        // 自动模式：优先硬件解码，失败时自动回退到软件解码
        val factory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(mode == DECODER_AUTO)
        if (mode == DECODER_AUTO) return factory

        return factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (mode == DECODER_SOFTWARE) {
                decoders.filter { it.softwareOnly }
            } else {
                decoders.filterNot { it.softwareOnly }
            }
        }
    }

    private fun rebuildPlayer() {
        val session = mediaSession ?: return
        val old = player ?: return

        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val repeatMode = old.repeatMode
        val shuffleEnabled = old.shuffleModeEnabled

        val fresh = buildPlayer()
        player = fresh
        session.player = fresh

        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            fresh.prepare()
        }
        fresh.repeatMode = repeatMode
        fresh.shuffleModeEnabled = shuffleEnabled
        fresh.playWhenReady = playWhenReady
        old.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 关闭画中画小窗、或从最近任务里划掉应用时，任务会被移除。
     * MediaSessionService 默认在播放中继续运行，导致界面消失后仍在后台出声，
     * 因此播放中也要主动暂停并记录进度。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = mediaSession?.player
        if (current != null && (current.isPlaying || current.playWhenReady)) {
            val item = current.currentMediaItem
            val uri = item?.localConfiguration?.uri?.toString()
            if (uri != null) {
                HistoryRepository.record(
                    uri,
                    item.mediaMetadata.title?.toString().orEmpty(),
                    current.currentPosition.coerceAtLeast(0L),
                    current.duration.takeIf { it > 0 } ?: 0L
                )
            }
            current.pause()
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        player = null
        runCatching { mediaCache?.release() }
        mediaCache = null
        runCatching { cacheDatabase?.close() }
        cacheDatabase = null
        super.onDestroy()
    }

    private companion object {
        const val DECODER_AUTO = 0
        const val DECODER_SOFTWARE = 1
        const val DECODER_HARDWARE = 2
    }
}

/**
 * 当前实际使用的视频解码器：播放服务在解码器初始化时写入，播放界面读取展示。
 * Media3 的 Player 接口不暴露解码器信息，因此用同进程共享状态传递。
 */
object DecoderStatus {

    private val _decoderName = MutableStateFlow<String?>(null)
    val decoderName: StateFlow<String?> = _decoderName.asStateFlow()

    fun update(name: String?) {
        _decoderName.value = name
    }

    /** 判断解码器是否为硬件解码（Google/AOSP 提供的通用实现视为软解） */
    fun isHardware(name: String): Boolean {
        val lower = name.lowercase()
        val software = lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("c2.google.") ||
            lower.contains("software") ||
            lower.contains("ffmpeg")
        return !software
    }
}
