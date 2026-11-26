package com.muse.player.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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

/** 可识别的播放链接协议，与应用声明的 intent 过滤器保持一致 */
private val playableUrlRegex =
    Regex("(?i)(?:https?|rtsp|rtp|rtmp|mms)://[^\\s\"'<>，。；、）】]+")

/** 从剪切板文本中提取第一个可播放的链接 */
private fun playableUrlFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
    val raw = playableUrlRegex.find(text)?.value ?: return null
    val url = raw.trimEnd('.', ',', ')', ']', '，', '。', '、', '）', '】')
    return url.takeIf { Uri.parse(it).host?.isNotBlank() == true }
}

@Composable
fun MuseApp() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val networkViewModel: NetworkViewModel = viewModel()
    val context = LocalContext.current

    // 启动时检查剪切板中是否有可播放的链接，有则询问是否播放
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        clipboardUrl = playableUrlFromClipboard(context)
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
                TextButton(onClick = { clipboardUrl = null }) { Text("取消") }
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
