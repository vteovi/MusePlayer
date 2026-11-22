package com.muse.player.playback.vlc

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * libVLC 播放引擎，作为 Media3 的兜底。
 *
 * Media3 只能走系统解码器，遇到设备不支持的编码（如 10bit H.265）无法播放；
 * libVLC 内置 FFmpeg，可以软件解码这些格式，因此在这些文件上切换到本引擎。
 */
class VlcEngine(context: Context) {

    companion object {
        /** 完整版带 libVLC 原生库，可做解码兜底 */
        const val SUPPORTED = true
    }


    data class VlcState(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val ended: Boolean = false,
        val error: String? = null
    )

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--network-caching=2000",
            "--file-caching=2000"
        )
    )

    private var mediaPlayer: MediaPlayer? = null
    private var media: Media? = null
    private var videoLayout: VLCVideoLayout? = null
    private var pendingSeekMs = 0L
    private var pendingSpeed = 1f

    private val _state = MutableStateFlow(VlcState())
    val state: StateFlow<VlcState> = _state.asStateFlow()

    /** 画面输出容器由界面提供，重建界面时重新挂载 */
    fun attachView(view: VLCVideoLayout) {
        videoLayout = view
        mediaPlayer?.attachViews(view, null, true, false)
    }

    fun play(url: String, startMs: Long, speed: Float) {
        stop()
        pendingSeekMs = startMs.coerceAtLeast(0L)
        pendingSpeed = speed

        val player = MediaPlayer(libVlc)
        mediaPlayer = player
        player.setEventListener { event -> onEvent(event) }

        val item = Media(libVlc, Uri.parse(url)).apply {
            // 兜底场景正是硬解不支持，强制走 VLC 自己的软解
            setHWDecoderEnabled(false, false)
            addOption(":network-caching=2000")
            addOption(":file-caching=2000")
        }
        media = item
        player.media = item
        videoLayout?.let { player.attachViews(it, null, true, false) }
        player.play()
        _state.value = VlcState(buffering = true)
    }

    /** 播放中追加字幕（远程字幕等），select = true 自动选中 */
    fun addSubtitle(url: String) {
        val player = mediaPlayer ?: return
        runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true) }
    }

    fun toggle() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    fun resume() {
        mediaPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        player.time = positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun setRate(rate: Float) {
        pendingSpeed = rate
        mediaPlayer?.rate = rate
    }

    /** 固定比例时交给 VLC 拉伸，传 null 恢复按视频原始比例 */
    fun setAspectRatio(ratio: String?) {
        val player = mediaPlayer ?: return
        player.setAspectRatio(ratio)
        player.setScale(0f)
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching { player.setEventListener(null) }
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        runCatching { media?.release() }
        media = null
    }

    fun release() {
        stop()
        runCatching { libVlc.release() }
    }

    private fun onEvent(event: MediaPlayer.Event) {
        val player = mediaPlayer ?: return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                if (pendingSeekMs > 0) {
                    player.time = pendingSeekMs
                    pendingSeekMs = 0
                }
                player.rate = pendingSpeed
                _state.update {
                    it.copy(
                        playing = true,
                        buffering = false,
                        durationMs = player.length.coerceAtLeast(0L),
                        error = null
                    )
                }
            }

            MediaPlayer.Event.Paused -> _state.update { it.copy(playing = false, buffering = false) }

            MediaPlayer.Event.Buffering -> {
                val buffering = event.buffering < 100f
                _state.update { it.copy(buffering = buffering) }
            }

            MediaPlayer.Event.TimeChanged -> _state.update {
                it.copy(
                    positionMs = player.time.coerceAtLeast(0L),
                    durationMs = player.length.coerceAtLeast(0L)
                )
            }

            MediaPlayer.Event.LengthChanged -> _state.update {
                it.copy(durationMs = player.length.coerceAtLeast(0L))
            }

            MediaPlayer.Event.EndReached -> _state.update {
                it.copy(playing = false, ended = true, buffering = false)
            }

            MediaPlayer.Event.EncounteredError -> _state.update {
                it.copy(
                    playing = false,
                    buffering = false,
                    error = "播放失败：兼容引擎也无法播放该视频"
                )
            }
        }
    }
}
