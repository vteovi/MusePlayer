package com.muse.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 导航栏全部分栏，用于保证至少保留一个，同时作为默认顺序
private val TAB_KEYS = listOf("MEDIA", "HISTORY", "BROWSE", "NETWORK")

data class AppSettings(
    val themeMode: Int = 0,          // 0 跟随系统, 1 浅色, 2 深色
    val autoResume: Boolean = true,  // 续播
    val backgroundPlay: Boolean = false, // 离开播放器后继续播放
    val autoSubtitles: Boolean = true,   // 自动加载同名字幕
    val showHiddenFolders: Boolean = false, // 是否显示已隐藏文件夹
    val hiddenFolders: Set<String> = emptySet(), // 被隐藏的文件夹 bucketId
    val showSystemHiddenFolders: Boolean = false, // 是否扫描并显示以 . 开头的系统隐藏文件夹
    val folderViewMode: Boolean = false, // 媒体库默认展示文件夹视图
    val gridViewMode: Boolean = false, // 视频以列表展示（true 为网格）
    val sortField: String = "DATE", // 媒体库排序字段（SortField 名称）
    val sortAscending: Boolean = false, // 媒体库排序是否升序
    val seekGestureSeconds: Int = 300, // 全屏宽度左右滑动可调节的时长（秒）
    val longPressSpeed: Float = 2f, // 长按画面临时倍速播放的倍率（最低 2x）
    val doubleTapSeekSeconds: Int = 10, // 双击画面两侧快进/快退的秒数
    val showMediaNotification: Boolean = false, // 通知栏媒体播放卡片（默认隐藏）
    val customAspectWidth: Int = 16, // 自定义画面比例的宽
    val customAspectHeight: Int = 9, // 自定义画面比例的高
    val decoderMode: Int = 0, // 0 自动（硬解优先+软解回退）, 1 强制软件解码, 2 强制硬件解码
    val repeatMode: Int = 0, // 0 顺序播放, 1 单曲循环, 2 列表循环
    val shuffleEnabled: Boolean = false, // 随机播放
    val lowPerformanceMode: Boolean = false, // 低性能模式：不加载视频缩略图
    val hiddenTabs: Set<String> = emptySet(), // 导航栏中隐藏的分栏（MEDIA / HISTORY / BROWSE / NETWORK）
    val tabOrder: List<String> = TAB_KEYS // 导航栏分栏顺序
)

object SettingsRepository {
    private const val PREFS = "muse_settings"
    private lateinit var sp: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = AppSettings(
            themeMode = sp.getInt("theme_mode", 0),
            autoResume = sp.getBoolean("auto_resume", true),
            backgroundPlay = sp.getBoolean("background_play", false),
            autoSubtitles = sp.getBoolean("auto_subtitles", true),
            showHiddenFolders = sp.getBoolean("show_hidden_folders", false),
            hiddenFolders = sp.getStringSet("hidden_folders", emptySet())?.toSet() ?: emptySet(),
            showSystemHiddenFolders = sp.getBoolean("show_system_hidden_folders", false),
            folderViewMode = sp.getBoolean("folder_view_mode", false),
            gridViewMode = sp.getBoolean("grid_view_mode", false),
            sortField = sp.getString("sort_field", "DATE") ?: "DATE",
            sortAscending = sp.getBoolean("sort_ascending", false),
            seekGestureSeconds = sp.getInt("seek_gesture_seconds", 300),
            longPressSpeed = sp.getFloat("long_press_speed", 2f),
            doubleTapSeekSeconds = sp.getInt("double_tap_seek_seconds", 10),
            showMediaNotification = sp.getBoolean("media_notification", false),
            customAspectWidth = sp.getInt("custom_aspect_width", 16),
            customAspectHeight = sp.getInt("custom_aspect_height", 9),
            decoderMode = sp.getInt("decoder_mode", 0),
            repeatMode = sp.getInt("repeat_mode", 0),
            shuffleEnabled = sp.getBoolean("shuffle_enabled", false),
            lowPerformanceMode = sp.getBoolean("low_performance_mode", false),
            hiddenTabs = sp.getStringSet("hidden_tabs", emptySet())?.toSet() ?: emptySet(),
            tabOrder = sp.getString("tab_order", null)
                ?.split(',')
                ?.filter { it.isNotBlank() }
                // 补上历史数据里可能缺失的分栏，保证四个都在
                ?.let { saved -> saved + TAB_KEYS.filterNot(saved::contains) }
                ?: TAB_KEYS
        )
    }

    fun setThemeMode(value: Int) {
        sp.edit().putInt("theme_mode", value).apply()
        _state.value = _state.value.copy(themeMode = value)
    }

    fun setAutoResume(value: Boolean) {
        sp.edit().putBoolean("auto_resume", value).apply()
        _state.value = _state.value.copy(autoResume = value)
    }

    fun setBackgroundPlay(value: Boolean) {
        sp.edit().putBoolean("background_play", value).apply()
        _state.value = _state.value.copy(backgroundPlay = value)
    }

    fun setAutoSubtitles(value: Boolean) {
        sp.edit().putBoolean("auto_subtitles", value).apply()
        _state.value = _state.value.copy(autoSubtitles = value)
    }

    fun setShowHiddenFolders(value: Boolean) {
        sp.edit().putBoolean("show_hidden_folders", value).apply()
        _state.value = _state.value.copy(showHiddenFolders = value)
    }

    fun setFolderViewMode(value: Boolean) {
        sp.edit().putBoolean("folder_view_mode", value).apply()
        _state.value = _state.value.copy(folderViewMode = value)
    }

    fun setGridViewMode(value: Boolean) {
        sp.edit().putBoolean("grid_view_mode", value).apply()
        _state.value = _state.value.copy(gridViewMode = value)
    }

    fun setSort(field: String, ascending: Boolean) {
        sp.edit()
            .putString("sort_field", field)
            .putBoolean("sort_ascending", ascending)
            .apply()
        _state.value = _state.value.copy(sortField = field, sortAscending = ascending)
    }

    fun setFolderHidden(bucketId: String, hidden: Boolean) {
        val current = _state.value.hiddenFolders
        val updated = if (hidden) current + bucketId else current - bucketId
        if (updated == current) return
        sp.edit().putStringSet("hidden_folders", updated).apply()
        _state.value = _state.value.copy(hiddenFolders = updated)
    }

    fun toggleFolderHidden(bucketId: String) {
        setFolderHidden(bucketId, bucketId !in _state.value.hiddenFolders)
    }

    fun setShowSystemHiddenFolders(value: Boolean) {
        sp.edit().putBoolean("show_system_hidden_folders", value).apply()
        _state.value = _state.value.copy(showSystemHiddenFolders = value)
    }

    fun setSeekGestureSeconds(value: Int) {
        val safe = value.coerceIn(60, 3600)
        sp.edit().putInt("seek_gesture_seconds", safe).apply()
        _state.value = _state.value.copy(seekGestureSeconds = safe)
    }

    fun setLongPressSpeed(value: Float) {
        // 支持 2x 到 8x，以 0.5x 为步进，最低 2x
        val safe = (kotlin.math.round(value * 2f) / 2f).coerceIn(2f, 8f)
        sp.edit().putFloat("long_press_speed", safe).apply()
        _state.value = _state.value.copy(longPressSpeed = safe)
    }

    fun setDoubleTapSeekSeconds(value: Int) {
        val safe = value.coerceIn(5, 60)
        sp.edit().putInt("double_tap_seek_seconds", safe).apply()
        _state.value = _state.value.copy(doubleTapSeekSeconds = safe)
    }

    fun setShowMediaNotification(value: Boolean) {
        sp.edit().putBoolean("media_notification", value).apply()
        _state.value = _state.value.copy(showMediaNotification = value)
    }

    fun setCustomAspectRatio(width: Int, height: Int) {
        val w = width.coerceIn(1, 999)
        val h = height.coerceIn(1, 999)
        sp.edit()
            .putInt("custom_aspect_width", w)
            .putInt("custom_aspect_height", h)
            .apply()
        _state.value = _state.value.copy(customAspectWidth = w, customAspectHeight = h)
    }

    fun setDecoderMode(value: Int) {
        sp.edit().putInt("decoder_mode", value).apply()
        _state.value = _state.value.copy(decoderMode = value)
    }

    fun setRepeatMode(value: Int) {
        sp.edit().putInt("repeat_mode", value).apply()
        _state.value = _state.value.copy(repeatMode = value)
    }

    fun setShuffleEnabled(value: Boolean) {
        sp.edit().putBoolean("shuffle_enabled", value).apply()
        _state.value = _state.value.copy(shuffleEnabled = value)
    }

    fun setLowPerformanceMode(value: Boolean) {
        sp.edit().putBoolean("low_performance_mode", value).apply()
        _state.value = _state.value.copy(lowPerformanceMode = value)
    }

    /** 控制导航栏分栏的显示与隐藏，key 为 MEDIA / HISTORY / BROWSE / NETWORK */
    fun setTabVisible(key: String, visible: Boolean) {
        val current = _state.value.hiddenTabs
        val updated = if (visible) current - key else current + key
        if (updated == current) return
        // 至少保留一个分栏
        if (TAB_KEYS.all { it in updated }) return
        sp.edit().putStringSet("hidden_tabs", updated).apply()
        _state.value = _state.value.copy(hiddenTabs = updated)
    }

    /** 把分栏移动到指定位置，供拖动排序使用 */
    fun moveTabTo(key: String, to: Int) {
        val order = _state.value.tabOrder.toMutableList()
        val from = order.indexOf(key)
        if (from == -1) return
        val target = to.coerceIn(0, order.lastIndex)
        if (target == from) return
        order.add(target, order.removeAt(from))
        sp.edit().putString("tab_order", order.joinToString(",")).apply()
        _state.value = _state.value.copy(tabOrder = order)
    }
}
