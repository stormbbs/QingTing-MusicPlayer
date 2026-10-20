package com.tom.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.data.online.OnlineSourceStore
import com.tom.musicplayer.player.PlayerDataSources
import com.tom.musicplayer.player.PlayerController

/** 「我的」：下载、歌单、音源，以及下载位置与音质这些偏好设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    onOpenDownloads: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val downloadCount = remember(version) { DownloadStore.load(context).size }
    val sourceCount = remember(version) { OnlineSourceStore.enabled(context).size }
    var downloadTarget by remember { mutableStateOf(Prefs.getDownloadTarget(context)) }
    var quality by remember { mutableStateOf(Prefs.getQuality(context)) }
    // 清完缓存要让「占用多少」重新算一遍，所以用版本号带着它重算
    val cacheText = remember(version) { formatMB(PlayerDataSources.cachedBytes(context)) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "我的",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            EntryCard(
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                title = "我的下载",
                subtitle = if (downloadCount > 0) "$downloadCount 首" else "还没有下载",
                onClick = onOpenDownloads
            )
            Spacer(modifier = Modifier.height(10.dp))
            EntryCard(
                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                title = "我的歌单",
                subtitle = "自建的歌单，跟着 App 走，不依赖音源",
                onClick = onOpenPlaylists
            )
            Spacer(modifier = Modifier.height(10.dp))
            EntryCard(
                icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                title = "在线音源",
                subtitle = if (sourceCount > 0) "$sourceCount 个音源已启用" else "还没有添加音源",
                onClick = onOpenSources
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("下载位置")
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                ChoiceChip(
                    text = "App 内（我的下载）",
                    selected = downloadTarget == "app",
                    onClick = {
                        downloadTarget = "app"
                        Prefs.saveDownloadTarget(context, "app")
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                ChoiceChip(
                    text = "音乐文件夹",
                    selected = downloadTarget == "saf",
                    onClick = {
                        downloadTarget = "saf"
                        Prefs.saveDownloadTarget(context, "saf")
                    }
                )
            }
            Text(
                text = if (downloadTarget == "app")
                    "下载的歌只在「我的下载」里，卸载 App 会一起清掉。"
                else "直接下载到你选的音乐文件夹，下载完会被自动扫描，和本地歌一起听。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("在线音质")
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                listOf("standard" to "标准", "high" to "高品", "lossless" to "无损")
                    .forEach { (value, label) ->
                        ChoiceChip(
                            text = label,
                            selected = quality == value,
                            onClick = {
                                quality = value
                                Prefs.saveQuality(context, value)
                                // 换档后已解析的直链作废，下一次播放会按新音质重新解析
                                com.tom.musicplayer.data.online.OnlineApi.clearUrlCache()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
            }
            Text(
                text = "音源不一定支持你选的档位，会退到它能给的最接近的一档。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("缓存")
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("边播边存的缓存", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "当前占用 $cacheText，听过一次就能离线重播",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        PlayerDataSources.clearCache(context)
                        version++
                    }) { Text("清理") }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) }
    )
}

@Composable
private fun EntryCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) { icon() }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMB(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
