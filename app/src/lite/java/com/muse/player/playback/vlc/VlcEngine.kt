package com.muse.player.playback.vlc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 轻量版占位实现：包内不含 libVLC 原生库，接口与完整版保持一致，
 * 让共用代码可以直接编译；实际不会被调用（SUPPORTED = false）。
 */
@Suppress("UNUSED_PARAMETER")
class VlcEngine(context: Context) {

    companion object {
        /** 轻量版不带原生库，不做解码兜底 */
        const val SUPPORTED = false
    }

    data class VlcState(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val ended: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(VlcState())
    val state: StateFlow<VlcState> = _state

    fun attachView(view: VLCVideoLayout) = Unit

    fun play(url: String, startMs: Long, speed: Float) = Unit

    fun addSubtitle(url: String) = Unit

    fun toggle() = Unit

    fun pause() = Unit

    fun resume() = Unit

    fun seekTo(positionMs: Long) = Unit

    fun setRate(rate: Float) = Unit

    fun setAspectRatio(ratio: String?) = Unit

    fun stop() = Unit

    fun release() = Unit
}
