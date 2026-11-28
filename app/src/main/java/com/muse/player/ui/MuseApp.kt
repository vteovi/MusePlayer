package com.muse.player.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muse.player.data.PlayEntry
import com.muse.player.ui.about.AboutScreen
import com.muse.player.ui.library.LibraryScreen
import com.muse.player.ui.library.LibraryViewModel
import com.muse.player.ui.network.NetworkViewModel
import com.muse.player.ui.player.PlayerLauncher
import com.muse.player.ui.settings.SettingsScreen
import com.muse.player.util.NetworkTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 可识别的播放链接协议，与应用声明的 intent 过滤器保持一致 */
private val playableUrlRegex =
    Regex("(?i)(?:https?|rtsp|rtp|rtmp|mms)://[^\\s\"'<>，。；、）】]+")

/** 流媒体专用协议，本身只能用于播放，无需再探测 */
private val streamSchemes = setOf("rtsp", "rtp", "rtmp", "mms")

/** 常见媒体文件后缀，命中后无需再做网络探测 */
private val mediaExtensions = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m2ts",
    "mpg", "mpeg", "3gp", "3g2", "rmvb", "rm", "asf", "vob", "m3u8", "mpd", "m3u",
    "mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "ape", "wma"
)

/** 从剪切板文本中提取第一个候选链接（协议符合，但未必真的能播放） */
private fun candidateUrlFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
    val raw = playableUrlRegex.find(text)?.value ?: return null
    val url = raw.trimEnd('.', ',', ')', ']', '，', '。', '、', '）', '】')
    return url.takeIf { Uri.parse(it).host?.isNotBlank() == true }
}

/** 协议或后缀就能确认是媒体链接时直接通过，省掉一次网络请求 */
private fun isObviousMediaUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase()?.let { it in streamSchemes } == true) return true
    val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: return false
    return extension in mediaExtensions
}

/** 网页、图片等普通链接也会命中正则，读取远端响应确认是否真的是可播放的媒体资源 */
private fun probePlayableMedia(url: String): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MusePlayer")
            setRequestProperty("Range", "bytes=0-2047")
        }
        if (connection.responseCode !in 200..399) return false
        val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.startsWith("video/") || type.startsWith("audio/")) return true
        if (type.contains("mpegurl") || type.contains("dash+xml") || type.contains("mp2t") ||
            type.contains("matroska") || type.contains("x-flv")
        ) {
            return true
        }
        // 类型缺失或服务器只给了 octet-stream 时，读取文件头判断容器格式
        val head = ByteArray(2048)
        var length = 0
        connection.inputStream.use { input ->
            while (length < head.size) {
                val read = input.read(head, length, head.size - length)
                if (read <= 0) break
                length += read
            }
        }
        looksLikeMediaHeader(head, length)
    } catch (_: Throwable) {
        false
    } finally {
        try {
            connection?.disconnect()
        } catch (_: Throwable) {
        }
    }
}

/** 按文件头判断常见媒体容器（HLS / DASH / MP4 / MKV / TS / FLV / AVI / ASF / Ogg） */
private fun looksLikeMediaHeader(head: ByteArray, length: Int): Boolean {
    if (length <= 0) return false
    val text = String(head, 0, length, Charsets.ISO_8859_1)
    if (text.startsWith("#EXTM3U")) return true
    if (text.trimStart().startsWith("<?xml") && text.contains("<MPD")) return true
    if (text.startsWith("FLV")) return true
    if (length > 8 && text.substring(4, 8) == "ftyp") return true
    if (length > 12 && text.startsWith("RIFF") && text.substring(8, 12) == "AVI ") return true
    if (length > 4 && text.startsWith("OggS")) return true
    if (length > 4 &&
        (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
        (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
    ) {
        return true
    }
    // MPEG-TS：0x47 同步字节每 188 字节出现一次
    if (length > 376 &&
        (head[0].toInt() and 0xFF) == 0x47 && (head[188].toInt() and 0xFF) == 0x47
    ) {
        return true
    }
    // ASF / WMV
    val asf = byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11)
    return length > asf.size && asf.indices.all { head[it] == asf[it] }
}

/** 判断链接是否真的可播放：协议/后缀命中直接通过，否则探测远端响应 */
private suspend fun isPlayableUrl(url: String): Boolean =
    isObviousMediaUrl(url) || withContext(Dispatchers.IO) { probePlayableMedia(url) }

@Composable
fun MuseApp() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val networkViewModel: NetworkViewModel = viewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 启动时以及每次从多任务切回前台，都检查剪切板中是否有可播放的链接
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    // 已提示过的链接，避免同一链接反复弹窗；剪切板换成新链接后会再次提示
    var handledUrl by remember { mutableStateOf<String?>(null) }
    // 已探测过且确认不可播放的链接，避免每次回到前台都重复请求
    var unplayableUrl by remember { mutableStateOf<String?>(null) }
    var checkTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(checkTrigger) {
        if (clipboardUrl != null) return@LaunchedEffect
        // 稍等片刻再读，确保窗口已获得焦点（Android 10+ 仅前台可读剪切板）
        delay(350)
        val url = candidateUrlFromClipboard(context) ?: return@LaunchedEffect
        if (url == handledUrl || url == unplayableUrl) return@LaunchedEffect
        // 普通网页链接也会命中正则，这里再确认远端确实是可播放的媒体，否则不弹窗
        if (isPlayableUrl(url)) clipboardUrl = url else unplayableUrl = url
    }

    clipboardUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { clipboardUrl = null },
            title = { Text("发现播放链接") },
            text = {
                Text(
                    text = url,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardUrl = null
                    handledUrl = url
                    PlayerLauncher.open(
                        context,
                        listOf(
                            PlayEntry(
                                uri = url,
                                title = NetworkTitles.of(url),
                                isNetwork = true
                            )
                        ),
                        0,
                        0L
                    )
                }) { Text("播放") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboardUrl = null
                    handledUrl = url
                }) { Text("取消") }
            }
        )
    }

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                navController = navController,
                viewModel = libraryViewModel,
                networkViewModel = networkViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate("about") }
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
