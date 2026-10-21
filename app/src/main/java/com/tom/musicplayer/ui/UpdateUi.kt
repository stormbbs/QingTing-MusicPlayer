package com.tom.musicplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.update.UpdateCenter

/**
 * 升级弹窗：有新版本、正在下载、等授权、装失败，全走这一个。
 *
 * 之所以只在这里弹一次（而不是播放页 / 设置页各弹一遍）：更新是全局状态，
 * 多处弹窗会在页面上叠出两个对话框，看起来像卡住了。
 */
@Composable
fun UpdateDialog(
    state: UpdateCenter.State,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val info = when (state) {
        is UpdateCenter.State.Available -> state.info
        is UpdateCenter.State.Downloading -> state.info
        is UpdateCenter.State.Ready -> state.info
        is UpdateCenter.State.NeedPermission -> state.info
        else -> null
    }
    if (info == null && state !is UpdateCenter.State.Failed) return

    AlertDialog(
        onDismissRequest = {
            // 下载中乱关弹窗，用户会以为下载断了；其他状态关掉就不打扰
            if (state !is UpdateCenter.State.Downloading) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is UpdateCenter.State.Downloading -> "正在下载新版本"
                    is UpdateCenter.State.Ready -> "安装包已就绪"
                    is UpdateCenter.State.NeedPermission -> "还需要一个系统授权"
                    is UpdateCenter.State.Failed -> "更新没成功"
                    else -> "发现新版本 ${info?.versionName}"
                }
            )
        },
        text = {
            Column {
                when (state) {
                    is UpdateCenter.State.Downloading -> {
                        Text("下载中 ${state.percent}%，可以切到后台，下完会自动拉起安装器。")
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is UpdateCenter.State.Ready -> {
                        Text("系统安装器已经打开，确认安装即可完成覆盖升级。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "覆盖安装不会清数据：下载记录、收藏、歌单、音源配置都保留，" +
                                "和卸载重装完全是两回事。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateCenter.State.NeedPermission -> {
                        Text("系统还没允许「轻听」安装应用。去设置里打开开关，回来再点安装。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "设置 → 安装未知应用 → 轻听 → 允许",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateCenter.State.Failed -> {
                        Text(state.message)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "也可以把新安装包传到手机上，从「我的 → 关于与更新 → 选择本地安装包」直接装，" +
                                "一样不用卸载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> Unit
                }

                if (info != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${info.versionName}（versionCode ${info.versionCode}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (info.sizeBytes > 0) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = formatSize(info.sizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (info.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "更新内容",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = info.notes,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateCenter.State.Available -> TextButton(onClick = {
                    UpdateCenter.download(context)
                }) { Text("立即更新") }
                is UpdateCenter.State.NeedPermission -> TextButton(onClick = {
                    UpdateCenter.installAfterPermission(context)
                }) { Text("我已授权，去安装") }
                is UpdateCenter.State.Ready -> TextButton(onClick = {
                    UpdateCenter.install(context)
                }) { Text("安装") }
                is UpdateCenter.State.Failed -> TextButton(onClick = {
                    UpdateCenter.check(context, manual = true)
                }) { Text("重试") }
                else -> TextButton(onClick = { }, enabled = false) { Text("下载中…") }
            }
        },
        dismissButton = {
            Row {
                if (state is UpdateCenter.State.Available) {
                    TextButton(onClick = { UpdateCenter.skip(context) }) { Text("忽略此版本") }
                }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        }
    )
}

/** 大小显示：10 MB / 1.2 GB。 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}

/**
 * 「我的 → 关于与更新」里的那块卡片。
 *
 * 这里不弹窗，只做三件事：显示当前版本、手动检查、配置更新源；
 * 真正的下载/安装交互交给 [UpdateDialog]，状态是同一份，不会出现两处不同步。
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by UpdateCenter.state.collectAsStateWithLifecycle()
    var autoCheck by remember { mutableStateOf(Prefs.getAutoUpdate(context)) }
    var customUrl by remember { mutableStateOf(Prefs.getUpdateUrl(context)) }
    var editingUrl by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) UpdateCenter.installFromFile(context, uri)
    }

    Column(modifier = modifier) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前版本", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${UpdateCenter.currentVersionName}（versionCode " +
                                "${UpdateCenter.currentVersionCode}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { UpdateCenter.check(context, manual = true) },
                        enabled = state !is UpdateCenter.State.Checking
                    ) {
                        when (state) {
                            is UpdateCenter.State.Checking -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("检查中")
                            }
                            else -> {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("检查更新")
                            }
                        }
                    }
                }

                // 检查结果就地反馈一句，省得用户点了按钮不知道有没有动
                val hint = when (val s = state) {
                    is UpdateCenter.State.UpToDate -> "已是最新版本 ${s.current}"
                    is UpdateCenter.State.Available -> "发现新版本 ${s.info.versionName}，点下面更新"
                    is UpdateCenter.State.Downloading -> "正在下载 ${s.percent}%"
                    is UpdateCenter.State.Ready -> "安装包已就绪，返回上一屏即可安装"
                    is UpdateCenter.State.NeedPermission -> "还需要允许「安装未知应用」"
                    is UpdateCenter.State.Failed -> "检查失败：${s.message}"
                    else -> null
                }
                hint?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is UpdateCenter.State.Failed)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时自动检查更新", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "只查版本号，不下载；发现新版本才提示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            Prefs.saveAutoUpdate(context, it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("更新源", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = customUrl.ifBlank { "默认（仓库里的 latest.json）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    TextButton(onClick = { editingUrl = true }) { Text("修改") }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("本地安装包", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "已经从别处拿到 APK 时，选它直接覆盖安装",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                apkPicker.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            }
                        }
                    ) { Text("选择") }
                }
            }
        }
    }

    if (editingUrl) {
        var draft by remember { mutableStateOf(customUrl) }
        AlertDialog(
            onDismissRequest = { editingUrl = false },
            title = { Text("自定义更新源") },
            text = {
                Column {
                    Text(
                        "填一个返回版本信息的 JSON 地址（格式见仓库 README）。留空即用默认源。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("https://…/latest.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "清单里 versionCode 要大于当前版本（${UpdateCenter.currentVersionCode}）" +
                            "才会被认为是新版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    customUrl = draft.trim()
                    Prefs.saveUpdateUrl(context, draft)
                    editingUrl = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        draft = ""
                        customUrl = ""
                        Prefs.saveUpdateUrl(context, "")
                        editingUrl = false
                    }) { Text("恢复默认") }
                    TextButton(onClick = { editingUrl = false }) { Text("取消") }
                }
            }
        )
    }
}
