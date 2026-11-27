package com.muse.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MediaRepository(private val context: Context) {

    /** 隐藏视频元数据缓存：路径 -> 修改时间/大小/时长/宽高 */
    private val metaCache by lazy {
        context.getSharedPreferences("hidden_video_meta", Context.MODE_PRIVATE)
    }

    /** 上次遍历得到的隐藏目录视频列表缓存，用于冷启动秒显 */
    private val scanCache by lazy {
        context.getSharedPreferences("hidden_video_scan", Context.MODE_PRIVATE)
    }

    suspend fun loadVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA
        )
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: "未知视频"
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.').ifBlank { name },
                        path = if (dataIdx >= 0) cursor.getString(dataIdx) else null,
                        durationMs = cursor.getLong(durationIdx),
                        size = cursor.getLong(sizeIdx),
                        dateAdded = cursor.getLong(dateIdx) * 1000L,
                        bucketId = cursor.getString(bucketIdIdx) ?: "0",
                        bucketName = cursor.getString(bucketNameIdx) ?: "未命名文件夹",
                        width = cursor.getInt(widthIdx),
                        height = cursor.getInt(heightIdx),
                        mimeType = cursor.getString(mimeIdx)
                    )
                }
            }
        } catch (_: Exception) {
            // 权限缺失或 MediaStore 异常时返回空列表
        }
        result
    }

    /**
     * 扫描外部存储中以 "." 开头的系统隐藏目录（如 .secret、.hidden）内的视频。
     * MediaStore 不会索引这类文件，只能通过文件系统遍历获取。
     * Android 11+ 需要“所有文件访问权限”，否则目录不可读，返回空列表。
     */
    suspend fun loadHiddenVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<VideoItem>()
        val budget = intArrayOf(MAX_SCAN_ENTRIES)
        for (root in externalRoots()) {
            scanDir(root, depth = 0, insideHidden = false, budget = budget, out = result)
            if (budget[0] <= 0) break
        }
        // 扫描结果为空通常是权限缺失或遍历失败，此时保留上次缓存，避免清空冷启动的秒显数据
        if (result.isNotEmpty()) saveCachedHiddenVideos(result)
        result
    }

    /**
     * 读取上次遍历得到的隐藏目录视频，供冷启动秒显。
     * 文件系统遍历需要一两秒，先用缓存顶上，后台扫描完成后再整体覆盖。
     */
    suspend fun loadCachedHiddenVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val raw = scanCache.getString("items", null) ?: return@withContext emptyList()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = obj.optString("p").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VideoItem(
                    id = negativeId(path),
                    uri = Uri.fromFile(File(path)),
                    title = obj.optString("t").ifBlank { path.substringAfterLast('/') },
                    path = path,
                    durationMs = obj.optLong("d"),
                    size = obj.optLong("s"),
                    dateAdded = obj.optLong("m"),
                    bucketId = obj.optString("b"),
                    bucketName = obj.optString("n").ifBlank { "隐藏文件夹" },
                    width = obj.optInt("w"),
                    height = obj.optInt("h"),
                    mimeType = null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCachedHiddenVideos(items: List<VideoItem>) {
        try {
            val arr = JSONArray()
            items.forEach { video ->
                val path = video.path ?: return@forEach
                arr.put(
                    JSONObject()
                        .put("p", path)
                        .put("t", video.title)
                        .put("d", video.durationMs)
                        .put("s", video.size)
                        .put("m", video.dateAdded)
                        .put("b", video.bucketId)
                        .put("n", video.bucketName)
                        .put("w", video.width)
                        .put("h", video.height)
                )
            }
            scanCache.edit().putString("items", arr.toString()).apply()
        } catch (_: Exception) {
            // 缓存写入失败不影响正常扫描结果
        }
    }

    /**
     * 逐个补齐隐藏视频的时长与分辨率。
     * 元数据解析较慢，交给调用方异步收集，避免拖慢媒体库首屏加载。
     */
    fun hiddenMetadataUpdates(items: List<VideoItem>): Flow<VideoItem> = channelFlow {
        items.chunked(4).forEach { chunk ->
            coroutineScope {
                chunk.map { item -> async { item.withFileMetadata() } }.awaitAll()
            }.forEach { send(it) }
        }
    }.flowOn(Dispatchers.IO)

    private fun externalRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        try {
            Environment.getExternalStorageDirectory()?.takeIf { it.exists() }?.let { roots += it }
        } catch (_: Exception) {
        }
        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
                var dir: File? = appDir
                while (dir != null && dir.name != "Android") dir = dir.parentFile
                dir?.parentFile?.takeIf { it.exists() }?.let { roots += it }
            }
        } catch (_: Exception) {
        }
        return roots.toList()
    }

    private fun scanDir(
        dir: File,
        depth: Int,
        insideHidden: Boolean,
        budget: IntArray,
        out: MutableList<VideoItem>
    ) {
        if (depth > MAX_SCAN_DEPTH || budget[0] <= 0) return
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return

        val subDirs = ArrayList<File>(children.size)
        for (file in children) {
            try {
                if (file.isDirectory) {
                    subDirs += file
                } else if (insideHidden || file.name.startsWith(".")) {
                    // 只统计视频候选文件，普通图片/文档不占用扫描预算
                    if (file.length() > 0L && file.extension.lowercase() in VIDEO_EXTENSIONS) {
                        out += file.toHiddenVideoItem()
                    }
                }
            } catch (_: Exception) {
                // 单个文件不可读时跳过
            }
        }

        // 隐藏目录优先扫描：普通大目录（相机、微信等）日志多，先扫 . 开头的才不会耗尽预算
        subDirs.sortBy { if (it.name.startsWith(".")) 0 else 1 }
        for (sub in subDirs) {
            if (budget[0]-- <= 0) return
            val name = sub.name
            if (depth == 0 && name == "Android") continue
            if (name in SKIP_DIRS) continue
            scanDir(
                dir = sub,
                depth = depth + 1,
                insideHidden = insideHidden || name.startsWith("."),
                budget = budget,
                out = out
            )
        }
    }

    private fun File.toHiddenVideoItem(): VideoItem {
        val absolute = absolutePath
        val fileSize = length()
        val modified = lastModified()
        // 文件未被改动时直接复用上次解析的时长/分辨率，避免重复解析
        val cached = readCachedMeta(absolute)?.takeIf { it.modified == modified && it.size == fileSize }
        return VideoItem(
            id = negativeId(absolute),
            uri = Uri.fromFile(this),
            title = name.substringBeforeLast('.').ifBlank { name },
            path = absolute,
            durationMs = cached?.durationMs ?: 0L,
            size = fileSize,
            dateAdded = modified,
            bucketId = "hidden:${parentFile?.absolutePath ?: absolute}",
            bucketName = parentFile?.name?.ifBlank { "隐藏文件夹" } ?: "隐藏文件夹",
            width = cached?.width ?: 0,
            height = cached?.height ?: 0,
            mimeType = null
        )
    }

    /** 读取隐藏视频的时长与分辨率，读取失败时保持 0，不影响展示 */
    private fun VideoItem.withFileMetadata(): VideoItem {
        val filePath = path ?: return this
        if (durationMs > 0L && width > 0 && height > 0) return this
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val result = copy(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: durationMs,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: width,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: height
            )
            if (result.durationMs > 0L) {
                saveCachedMeta(
                    filePath,
                    CachedMeta(
                        modified = dateAdded,
                        size = size,
                        durationMs = result.durationMs,
                        width = result.width,
                        height = result.height
                    )
                )
            }
            result
        } catch (_: Exception) {
            this
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private data class CachedMeta(
        val modified: Long,
        val size: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int
    )

    private fun readCachedMeta(path: String): CachedMeta? {
        val raw = metaCache.getString(path, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 5) return null
        return try {
            CachedMeta(
                modified = parts[0].toLong(),
                size = parts[1].toLong(),
                durationMs = parts[2].toLong(),
                width = parts[3].toInt(),
                height = parts[4].toInt()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCachedMeta(path: String, meta: CachedMeta) {
        metaCache.edit()
            .putString(
                path,
                listOf(
                    meta.modified, meta.size, meta.durationMs, meta.width, meta.height
                ).joinToString("|")
            )
            .apply()
    }

    /** 生成稳定的负数 id，避免与 MediaStore 的正数 id 冲突 */
    private fun negativeId(path: String): Long {
        var hash = 1125899906842597L
        for (ch in path) hash = 31 * hash + ch.code
        return -((hash and Long.MAX_VALUE) + 1)
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 8
        const val MAX_SCAN_ENTRIES = 20_000
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v",
            "ts", "m2ts", "mpg", "mpeg", "3gp", "3g2", "rmvb", "rm", "asf", "vob"
        )
        val SKIP_DIRS = setOf(
            ".thumbnails", ".trashed", ".transforms", ".pending", ".cache",
            ".temporary", ".temp", ".recycle", ".recycler", ".Trash"
        )
    }
}
