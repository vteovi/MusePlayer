package com.muse.player.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.player.data.FolderItem
import com.muse.player.data.HistoryEntry
import com.muse.player.data.HistoryRepository
import com.muse.player.data.MediaRepository
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SortField
import com.muse.player.data.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val query: String = "",
    val sortField: SortField = SortField.DATE,
    val ascending: Boolean = false,
    val tab: Int = 0,
    val grid: Boolean = true,
    val hiddenFolderIds: Set<String> = emptySet(),
    val showHiddenFolders: Boolean = false
) {
    val filterActive: Boolean get() = query.isNotBlank()
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val _state = MutableStateFlow(
        LibraryUiState(
            sortField = runCatching { SortField.valueOf(SettingsRepository.state.value.sortField) }
                .getOrDefault(SortField.DATE),
            ascending = SettingsRepository.state.value.sortAscending
        )
    )
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var mediaVideos: List<VideoItem> = emptyList()
    private var hiddenVideos: List<VideoItem> = emptyList()

    /** MediaStore 结果与隐藏目录视频合并，路径重复时以 MediaStore 为准 */
    private val allVideos: List<VideoItem>
        get() {
            if (hiddenVideos.isEmpty()) return mediaVideos
            val paths = mediaVideos.mapNotNullTo(HashSet()) { it.path }
            return mediaVideos + hiddenVideos.filter { it.path !in paths }
        }
    private var hiddenJob: Job? = null
    private var metadataJob: Job? = null
    private var lastShowSystemHidden = SettingsRepository.state.value.showSystemHiddenFolders

    init {
        viewModelScope.launch {
            HistoryRepository.entries.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            SettingsRepository.state.collect { settings ->
                _state.update {
                    it.copy(
                        hiddenFolderIds = settings.hiddenFolders,
                        showHiddenFolders = settings.showHiddenFolders,
                        grid = settings.gridViewMode
                    )
                }
                val systemHiddenChanged = settings.showSystemHiddenFolders != lastShowSystemHidden
                lastShowSystemHidden = settings.showSystemHiddenFolders
                if (systemHiddenChanged) refresh() else rebuild()
            }
        }
        refresh()
    }

    fun refresh() {
        hiddenJob?.cancel()
        metadataJob?.cancel()
        viewModelScope.launch {
            val showSystemHidden = SettingsRepository.state.value.showSystemHiddenFolders
            // 隐藏目录只能遍历文件系统，先用上次扫描的缓存秒显，避免每次冷启动都等一两秒
            hiddenVideos = if (showSystemHidden) repository.loadCachedHiddenVideos() else emptyList()
            _state.update { it.copy(loading = true) }
            // 先展示 MediaStore 结果，保证首屏秒显
            val visible = repository.loadVideos()
            mediaVideos = visible
            rebuild()
            _state.update { it.copy(loading = false) }

            if (!showSystemHidden) return@launch
            // 缓存先顶上，后台再完整扫描一次并覆盖
            hiddenJob = viewModelScope.launch hidden@{
                val hidden = repository.loadHiddenVideos()
                hiddenVideos = hidden
                rebuild()
                if (hidden.isEmpty()) return@hidden
                // 隐藏视频的时长/分辨率后台补齐，边补边刷新列表
                metadataJob = viewModelScope.launch {
                    repository.hiddenMetadataUpdates(hidden).collect { updated ->
                        hiddenVideos = hiddenVideos.map { if (it.id == updated.id) updated else it }
                        rebuild()
                    }
                }
            }
        }
    }

    private fun rebuild() {
        val current = _state.value
        val query = current.query.trim()

        var filtered = allVideos
        if (!current.showHiddenFolders && current.hiddenFolderIds.isNotEmpty()) {
            filtered = filtered.filterNot { it.bucketId in current.hiddenFolderIds }
        }
        if (query.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.bucketName.contains(query, ignoreCase = true)
            }
        }

        val comparator: Comparator<VideoItem> = when (current.sortField) {
            SortField.DATE -> compareBy { it.dateAdded }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortField.DURATION -> compareBy { it.durationMs }
            SortField.SIZE -> compareBy { it.size }
            SortField.FOLDER -> compareBy<VideoItem> { it.bucketName.lowercase() }
                .thenBy { it.title.lowercase() }
        }
        val sorted = filtered.sortedWith(comparator).let {
            if (current.ascending) it else it.reversed()
        }
        val folders = filtered
            .groupBy { it.bucketId }
            .map { (id, list) ->
                FolderItem(
                    bucketId = id,
                    name = list.first().bucketName,
                    count = list.size,
                    cover = list.maxByOrNull { it.dateAdded }
                )
            }
            .filter { folder -> query.isEmpty() || folder.name.contains(query, true) }
            .sortedWith(
                if (current.sortField == SortField.FOLDER) {
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                } else {
                    compareByDescending { it.cover?.dateAdded ?: 0L }
                }
            )

        _state.update { it.copy(videos = sorted, folders = folders) }
    }

    fun videosInFolder(bucketId: String): List<VideoItem> {
        val current = _state.value
        val comparator: Comparator<VideoItem> = when (current.sortField) {
            SortField.DATE -> compareBy { it.dateAdded }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortField.DURATION -> compareBy { it.durationMs }
            SortField.SIZE -> compareBy { it.size }
            SortField.FOLDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
        return allVideos.filter { it.bucketId == bucketId }
            .sortedWith(comparator)
            .let { if (current.ascending) it else it.reversed() }
    }

    fun setTab(tab: Int) = _state.update { it.copy(tab = tab) }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        rebuild()
    }

    fun toggleGrid() {
        val next = !_state.value.grid
        _state.update { it.copy(grid = next) }
        SettingsRepository.setGridViewMode(next)
    }

    fun setSortField(field: SortField) {
        _state.update {
            if (it.sortField == field) {
                it.copy(ascending = !it.ascending)
            } else {
                it.copy(sortField = field, ascending = field == SortField.NAME || field == SortField.FOLDER)
            }
        }
        val current = _state.value
        SettingsRepository.setSort(current.sortField.name, current.ascending)
        rebuild()
    }

    fun removeHistory(uri: String) = HistoryRepository.remove(uri)

    fun clearHistory() = HistoryRepository.clear()

    fun toggleFolderHidden(bucketId: String) = SettingsRepository.toggleFolderHidden(bucketId)
}
