package com.tom.musicplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * SAF 持久化授权工具。
 *
 * 各家 ROM / 文件管理器对 `takePersistableUriPermission` 接受的 flag 组合并不一致：
 * 有的只认纯 READ，有的要求带上 PREFIX，有的写法不兼容就直接抛 SecurityException。
 * 一旦申请失败（异常被吞掉），App 下次冷启动就再也读不到这个文件夹，
 * 表现就是「文件夹白选了、又要重新选」。这里逐个尝试并**校验结果**。
 */
object UriGrant {

    /** 是否已经持有该 Uri 的持久化读授权。 */
    fun isPersisted(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }.getOrDefault(false)

    /** 尽力登记持久化读授权，返回是否成功。 */
    fun take(context: Context, uri: Uri): Boolean {
        if (isPersisted(context, uri)) return true
        val combos = listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        for (flags in combos) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            if (isPersisted(context, uri)) return true
        }
        return false
    }

    /** 是否已经持有该 Uri 的持久化**写**授权（往授权文件夹里下载歌曲时需要）。 */
    fun isPersistedWrite(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }.getOrDefault(false)

    /**
     * 尽力登记持久化写授权。
     *
     * 选文件夹时拿到的授权组合各家 ROM 不一样，有的只给了读。这里逐个试并校验结果，
     * 全部失败时返回 false —— 调用方要据此提示用户「重新选一次文件夹」，
     * 而不是写了一半才抛 SecurityException。
     */
    fun takeWrite(context: Context, uri: Uri): Boolean {
        if (isPersistedWrite(context, uri)) return true
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val prefix = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        for (flags in listOf(
            read or write,
            read or write or prefix,
            read or write or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            write
        )) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            if (isPersistedWrite(context, uri)) return true
        }
        return false
    }

    /**
     * 兜底恢复：万一 Prefs 里的 treeUri 丢了，但系统还留着我们的持久化授权
     * （授权记在系统侧，不会跟着 App 数据消失），就用它把文件夹找回来。
     */
    fun recoverTree(context: Context): Uri? = runCatching {
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .firstOrNull { it.toString().contains("/tree/") }
    }.getOrNull()
}
