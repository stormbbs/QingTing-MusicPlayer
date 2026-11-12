package com.tom.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import android.content.Context
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.model.Song

/**
 * 选中歌单（或新建歌单）后真正把歌写进去。
 *
 * 在线页、下载页、曲库页的调用方只要给歌列表和 id / 新名字，
 * 不用各自再写一遍「先 create 再 add」和去重结果。
 *
 * @return 实际加进去几首（0 表示都已经在歌单里了）
 */
internal fun addToPlaylist(
    context: Context,
    songs: List<Song>,
    playlistId: String? = null,
    newName: String? = null
): Int {
    if (songs.isEmpty()) return 0
    val id = playlistId
        ?: newName?.takeIf { it.isNotBlank() }?.let { LocalPlaylistStore.create(context, it).id }
        ?: return 0
    return LocalPlaylistStore.addSongs(context, id, songs)
}

internal fun shortToast(context: Context, message: String) {
    runCatching {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 「加到哪个歌单」——在线页、下载页长按、曲库长按共用这一个弹窗。
 *
 * 以前只有在线页有这份对话框，下载和本地歌想进歌单没门路，
 * 各写一份又容易三处行为不一致（有的能新建、有的不能），所以抽出来。
 *
 * @param titles 要加进去的歌名，只用来显示一行提示；多首时显示「N 首」
 * @param onPick 选中已有歌单，参数是歌单 id
 * @param onCreate 输入名字新建歌单，参数是新名字（创建 + 加歌都由调用方完成）
 */
@Composable
fun PlaylistPickerDialog(
    titles: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    val context = LocalContext.current
    // 每次弹出来都重读一遍：歌单可能刚在别处新建 / 删掉
    val playlists = remember { LocalPlaylistStore.load(context) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    val caption = when {
        titles.isEmpty() -> "选一个歌单"
        titles.size == 1 -> titles.first()
        else -> "已选 ${titles.size} 首歌"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加到歌单") },
        text = {
            Column {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (playlists.isEmpty() || creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("新歌单名字") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (creating) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { creating = false }) { Text("返回选已有歌单") }
                    }
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        playlists.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(p.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "${p.songs.size} 首",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                        Text("新建歌单")
                    }
                }
            }
        },
        confirmButton = {
            if (creating || playlists.isEmpty()) {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { onCreate(newName.trim()) }
                ) { Text("创建并加入") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
