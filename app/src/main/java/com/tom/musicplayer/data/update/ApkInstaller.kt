package com.tom.musicplayer.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的安装包交给系统安装器。
 *
 * 关键点有三个，缺一个就「点了没反应」：
 * 1. 安装包必须通过 FileProvider 给出（Android 7 起直接给 file:// 会抛
 *    FileUriExposedException）；
 * 2. Android 8 起要用户显式允许「安装未知应用」，否则 startActivity 静默失败；
 * 3. 必须带 FLAG_GRANT_READ_URI_PERMISSION，不然系统装不了。
 *
 * 这么装上去等于原地覆盖升级：数据、下载的歌、设置全都保留，
 * 不像卸载重装那样把 App 私有数据一起清掉。
 */
object ApkInstaller {

    /** 安装包存这里：`cacheDir/updates`，与 FileProvider 的 update_paths 对应。 */
    fun cacheDir(context: Context): File =
        File(context.cacheDir, "updates").apply { runCatching { mkdirs() } }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /**
     * 打开「安装未知应用」的系统开关页。
     * @return 打不开（部分 ROM 没有这个页面）返回 false，调用方应提示用户手动去设置
     */
    fun requestPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 启动系统安装器。
     *
     * 这里比「setDataAndType + FLAG_GRANT_READ_URI_PERMISSION」多做了两件事，
     * 都是踩过坑的：
     * - 把 Uri 同时放进 clipData：安装器（InstallStart → InstallStaging）会把
     *   收到的 intent 再转一层，只有 clipData 里的 Uri 才跟着权限一起传下去；
     * - 显式 grantUriPermission 给真正处理安装的那个包：Android 11 起
     *   「不是自己进程发起的访问」越来越容易被判成未授权，
     *   只靠 flag 会出现「解析安装包时出问题」这种误导性报错。
     *
     * @return false 表示启动失败（没权限 / 文件不在 / ROM 拦截）
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = android.content.ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 谁去装就把读权限给谁。取不到就退回到只靠 flag，不影响流程继续
            runCatching {
                context.packageManager
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }.getOrNull()?.let { pkg ->
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 从文件管理器选的安装包：复制到缓存再装（外部的 content:// 只有一次读授权）。 */
    fun copyToCache(context: Context, source: Uri, name: String): File? {
        val target = File(cacheDir(context), name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        val ok = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } != null
        }.getOrDefault(false)
        return if (ok && target.length() > 0) target else null
    }

    /** 装完之后清掉缓存里的旧包，别一直占着几百兆。 */
    fun clear(context: Context) {
        runCatching { cacheDir(context).listFiles()?.forEach { it.delete() } }
    }
}
