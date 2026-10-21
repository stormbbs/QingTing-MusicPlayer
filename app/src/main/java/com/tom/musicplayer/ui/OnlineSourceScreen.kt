package com.tom.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.musicplayer.data.online.OnlineApi
import com.tom.musicplayer.data.online.OnlineSourceStore
import com.tom.musicplayer.model.BuiltinProvider
import com.tom.musicplayer.model.OnlineSource
import kotlinx.coroutines.launch

/**
 * 音源管理：在线音乐的数据从哪来，全在这里说了算。
 *
 * 支持手动添加、从一段 JSON（或指向它的 URL）导入、导出到剪贴板，
 * 以及「测一下能不能连上」——填错地址时不用等到搜索才发现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSourceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var version by remember { mutableIntStateOf(0) }
    val sources = remember(version) { OnlineSourceStore.load(context) }
    var editing by remember { mutableStateOf<OnlineSource?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addingBuiltin by remember { mutableStateOf<BuiltinProvider?>(null) }
    var picking by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "音源管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { picking = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加音源")
                }
            }
            Text(
                text = "内置引擎开箱即用，但要自己填一把音源密钥才能播放；也可以再加任何符合协议的第三方音源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { importOpen = true }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导入")
                }
                TextButton(onClick = {
                    val json = OnlineSourceStore.exportJson(context)
                    clipboard.setText(AnnotatedString(json))
                    toast = "已复制 ${sources.size} 个音源的配置"
                }) { Text("导出") }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${sources.count { it.enabled }} / ${sources.size} 启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (sources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("一个音源都没有", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "点右上角 + 手动添加，或用「导入」粘贴一份配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(sources, key = { it.id }) { src ->
                        SourceCard(
                            source = src,
                            onToggle = { on ->
                                OnlineSourceStore.toggle(context, src.id, on)
                                version++
                            },
                            onEdit = { editing = src },
                            onDelete = {
                                OnlineSourceStore.remove(context, src.id)
                                version++
                            },
                            onTest = { callback ->
                                scope.launch {
                                    val err = OnlineApi.test(src)
                                    callback(err)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    if (picking) {
        val missing = OnlineSourceStore.missingBuiltins(context)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("添加音源") },
            text = {
                Column {
                    Text(
                        text = "内置平台开箱即用，不用架服务，但要自己填一把音源密钥才能播放；" +
                            "也可以接入任何符合协议的第三方音源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    missing.forEach { p ->
                        PickRow(p.displayName, "内置引擎 · 需填自己的音源密钥") {
                            // 不再「点一下就加进列表」：先弹密钥对话框，填完才算装上。
                            // 否则列表里会留一个能搜不能播的空壳，用户还以为是坏了。
                            picking = false
                            addingBuiltin = p
                        }
                    }
                    PickRow("自定义协议音源", "填一个符合在线音源协议的服务地址") {
                        picking = false
                        creating = true
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text("取消") }
            }
        )
    }

    if (creating || editing != null) {
        SourceEditDialog(
            source = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { src ->
                OnlineSourceStore.upsert(context, src)
                version++
                creating = false
                editing = null
            }
        )
    }

    addingBuiltin?.let { p ->
        BuiltinKeyDialog(
            provider = p,
            onDismiss = { addingBuiltin = null },
            onSave = { key ->
                OnlineSourceStore.upsert(
                    context,
                    OnlineSourceStore.defaultBuiltin(p).copy(apiKey = key)
                )
                version++
                addingBuiltin = null
                toast = "已添加「${p.displayName}」"
            }
        )
    }

    if (importOpen) {
        ImportSourceDialog(
            onDismiss = { importOpen = false },
            onImport = { text ->
                val parsed = OnlineSourceStore.parseImport(text)
                val n = OnlineSourceStore.importSources(context, parsed)
                version++
                toast = if (n > 0) "导入/更新了 $n 个音源" else "这段文本里没有可用的音源"
                importOpen = false
            }
        )
    }

    toast?.let { msg ->
        AlertDialog(
            onDismissRequest = { toast = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { toast = null }) { Text("好") } }
        )
    }
}

@Composable
private fun SourceCard(
    source: OnlineSource,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: ((String?) -> Unit) -> Unit
) {
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (source.isBuiltin) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Tag("内置")
                        }
                    }
                    Text(
                        text = source.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (source.needsKey) {
                        Text(
                            text = "还没填密钥，只能搜索、不能播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        it.startsWith("可用") -> MaterialTheme.colorScheme.primary
                        it.contains("能搜索") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        onTest { err ->
                            testing = false
                            testResult = if (err == null) "可用：连接正常" else "连不上：$err"
                        }
                    }
                ) { Text(if (testing) "测试中" else "测试连接") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (source.isBuiltin) "设置" else "编辑")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除音源")
                }
            }
        }
    }
}

/** 小标签，「内置」这种。 */
@Composable
private fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** 「添加音源」里的一个选项。 */
@Composable
private fun PickRow(title: String, desc: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceEditDialog(
    source: OnlineSource?,
    onDismiss: () -> Unit,
    onSave: (OnlineSource) -> Unit
) {
    // 内置源只让改名字和密钥：服务地址、鉴权方式这些东西由 App 的引擎决定，改不了
    val builtin = source?.isBuiltin == true
    var name by remember { mutableStateOf(source?.name ?: "") }
    // 不留 "http://" 前缀做初值：它看着像提示，实际会变成地址的一部分，
    // 用户补完地址常常变成 "http://...://" 这种拼坏的结果
    var baseUrl by remember { mutableStateOf(source?.baseUrl ?: "") }
    var token by remember { mutableStateOf(source?.token ?: "") }
    var user by remember { mutableStateOf(source?.user ?: "") }
    var password by remember { mutableStateOf(source?.password ?: "") }
    var apiKey by remember { mutableStateOf(source?.apiKey ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    source == null -> "添加音源"
                    builtin -> "设置内置音源"
                    else -> "编辑音源"
                }
            )
        },
        text = {
            Column {
                if (builtin) {
                    Text(
                        text = "搜索、榜单、歌词由 App 内置引擎直接提供，不用填服务地址。" +
                            "播放要解析直链，所以需要你自己的音源密钥——App 不再预置密钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Field("显示名", name, { name = it }, source?.name.orEmpty())
                    Field("音源密钥", apiKey, { apiKey = it; error = null }, "CERU_KEY-...")
                    Text(
                        text = "密钥只存这台设备本地，不会上传。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Field("名字", name, { name = it }, "我的音源")
                    Field("服务地址", baseUrl, { baseUrl = it; error = null }, "https://music.example.com/api")
                    Field("令牌 token（可选）", token, { token = it }, "")
                    Field("用户名（可选）", user, { user = it }, "")
                    Field("密码（可选）", password, { password = it }, "")
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val current = source
                if (builtin) {
                    if (current != null) onSave(
                        current.copy(
                            name = name.trim().ifBlank { current.name },
                            apiKey = apiKey.trim()
                        )
                    )
                    return@TextButton
                }
                val url = baseUrl.trim()
                if (url.isBlank() || url == "http://" || url == "https://") {
                    error = "服务地址不能为空"
                    return@TextButton
                }
                onSave(
                    OnlineSource(
                        id = current?.id ?: OnlineSourceStore.newId(),
                        // 没填名字就用域名兜底。直接用整条 URL 的话，卡片上标题和
                        // 副标题会显示同一个字符串，看着像渲染重复了。
                        name = name.trim().ifBlank {
                            url.substringAfter("://").substringBefore("/")
                        },
                        baseUrl = url,
                        token = token.trim(),
                        user = user.trim(),
                        password = password,
                        enabled = current?.enabled ?: true
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 添加内置平台时先要一把密钥：没有密钥的源既不能播、也没必要占着列表。 */
@Composable
private fun BuiltinKeyDialog(
    provider: BuiltinProvider,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加「${provider.displayName}」") },
        text = {
            Column {
                Text(
                    text = "搜索、榜单、歌词由 App 内置引擎直接提供，不用填服务地址。" +
                        "播放要解析直链，所以需要你自己的音源密钥——App 已不再内置任何密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Field("音源密钥", key, { key = it }, "CERU_KEY-...")
                Text(
                    text = "密钥只存这台设备本地，不会上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank(), onClick = { onSave(key.trim()) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

@Composable
private fun ImportSourceDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入音源") },
        text = {
            Column {
                Text(
                    text = "粘贴音源配置的 JSON，或填一个返回该 JSON 的网址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onImport(text) }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
