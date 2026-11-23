package com.muse.player.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer

/**
 * 字幕时间偏移：正数表示字幕延后出现，负数表示提前。
 * 界面调整后写入这里，解码器在输出字幕时实时读取。
 */
object SubtitleOffset {
    @Volatile
    var offsetMs: Long = 0L

    val offsetUs: Long get() = offsetMs * 1000L
}

/**
 * 字幕解码器工厂：在默认解码结果上叠加时间偏移。
 * Media3 没有公开的字幕偏移接口，这里通过包装解码器实现。
 */
@OptIn(UnstableApi::class)
class OffsetSubtitleDecoderFactory : SubtitleDecoderFactory {

    override fun supportsFormat(format: Format): Boolean =
        SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        OffsetSubtitleDecoder(SubtitleDecoderFactory.DEFAULT.createDecoder(format))
}

/** 只能用传统字幕解码流程，才能接管解码结果做时间偏移 */
@OptIn(UnstableApi::class)
class OffsetRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val renderer = TextRenderer(output, outputLooper, OffsetSubtitleDecoderFactory())
        renderer.experimentalSetLegacyDecodingEnabled(true)
        out.add(renderer)
    }
}

@OptIn(UnstableApi::class)
private class OffsetSubtitleDecoder(
    private val delegate: SubtitleDecoder
) : SubtitleDecoder {

    override fun getName(): String = delegate.name

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) =
        delegate.setOutputStartTimeUs(outputStartTimeUs)

    override fun setPositionUs(positionUs: Long) = delegate.setPositionUs(positionUs)

    override fun dequeueInputBuffer(): SubtitleInputBuffer? = delegate.dequeueInputBuffer()

    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) =
        delegate.queueInputBuffer(inputBuffer)

    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        val buffer = delegate.dequeueOutputBuffer() ?: return null
        val offsetUs = SubtitleOffset.offsetUs
        return if (offsetUs == 0L) buffer else ShiftedSubtitleBuffer(buffer, offsetUs)
    }

    override fun flush() = delegate.flush()

    override fun release() = delegate.release()
}

/**
 * 把解码出的字幕整体平移 offsetUs。
 * 释放时归还原解码器的缓冲，避免其缓冲区被耗尽。
 */
@OptIn(UnstableApi::class)
private class ShiftedSubtitleBuffer(
    private val source: SubtitleOutputBuffer,
    private val offsetUs: Long
) : SubtitleOutputBuffer() {

    init {
        timeUs = source.timeUs + offsetUs
    }

    override fun getEventTimeCount(): Int = source.eventTimeCount

    override fun getEventTime(index: Int): Long = source.getEventTime(index) + offsetUs

    override fun getNextEventTimeIndex(timeUs: Long): Int =
        source.getNextEventTimeIndex(timeUs - offsetUs)

    override fun getCues(timeUs: Long): List<Cue> = source.getCues(timeUs - offsetUs)

    override fun release() {
        source.release()
    }
}
