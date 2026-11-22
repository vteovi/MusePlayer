package com.muse.player.data

import android.net.Uri
import com.muse.player.data.network.NetworkRepository
import com.muse.player.data.network.RemoteFile
import java.io.File

/**
 * 查找与视频同目录、同名的外挂字幕文件。
 */
object SubtitleFinder {
    private val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
    private val dirCache = HashMap<String, List<File>>()
    // 远程目录列表缓存，key 为 serverId/相对路径
    private val remoteCache = HashMap<String, List<RemoteFile>>()

    fun findFor(videoPath: String?): List<String> {
        if (videoPath.isNullOrBlank()) return emptyList()
        val file = File(videoPath)
        val dir = file.parentFile ?: return emptyList()
        val baseName = file.nameWithoutExtension
        val files = dirCache.getOrPut(dir.absolutePath) {
            try {
                dir.listFiles()?.filter { it.isFile } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return files
            .filter { candidate ->
                candidate.extension.lowercase() in EXTENSIONS &&
                    candidate.nameWithoutExtension.startsWith(baseName, ignoreCase = true)
            }
            .sortedBy { it.name }
            .take(8)
            .map { it.absolutePath }
    }

    /**
     * 远程视频（SMB / FTP / WebDAV）的同名字幕查找。
     * 播放地址形如 muse+smb://<serverId>/dir/movie.mkv，凭据仍从本机配置读取。
     */
    suspend fun findForRemote(videoUri: String?): List<String> {
        if (videoUri.isNullOrBlank()) return emptyList()
        val (server, path) = NetworkRepository.resolve(Uri.parse(videoUri)) ?: return emptyList()
        val fileName = path.substringAfterLast('/')
        if (fileName.isBlank()) return emptyList()
        val baseName = fileName.substringBeforeLast('.', fileName)
        if (baseName.isBlank()) return emptyList()
        val parent = path.substringBeforeLast('/', "")

        val cacheKey = "${server.id}/$parent"
        val entries = remoteCache[cacheKey] ?: run {
            val listed = try {
                NetworkRepository.list(server, parent)
            } catch (_: Exception) {
                emptyList()
            }
            remoteCache[cacheKey] = listed
            listed
        }

        return entries
            .filter { candidate ->
                !candidate.isDirectory &&
                    candidate.name.substringAfterLast('.', "").lowercase() in EXTENSIONS &&
                    candidate.name.substringBeforeLast('.', candidate.name)
                        .startsWith(baseName, ignoreCase = true)
            }
            .sortedBy { it.name }
            .take(8)
            .map { candidate ->
                candidate.uri.ifBlank {
                    NetworkRepository.buildPlayUri(server, candidate.relativePath)
                }
            }
    }
}
