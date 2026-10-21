package com.tom.musicplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台存活相关的系统设置。
 *
 * 说明一件容易被误解的事：Android 官方的 Doze / 电池优化只会「延后」后台任务的执行，
 * 而真正让音乐突然停掉、App 从最近任务里消失的，通常是三件事：
 *
 *   1) 没有 WAKE_LOCK，息屏后 CPU 直接睡下去（已在 Manifest 里补上）；
 *   2) 服务被系统判定为「可回收的后台服务」，进而在内存紧张时被 LMK 杀掉；
 *   3) 国产 ROM 自己的一套后台管控（省电策略、自启动、后台弹出界面等），
 *      跟 AOSP 的电池优化是两套东西，只加白名单并不够。
 *
 * 这里只做第 2 件能由 App 自己争取的部分：把应用加进电池优化白名单，
 * 并把当前状态如实告诉用户，不假装已经解决了所有情况。
 */
object PowerGuard {

    /** 是否已被排除在电池优化之外（= 允许后台自由运行）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求加入白名单。
     *
     * 会先尝试直接弹系统确认框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），
     * 部分 ROM 屏蔽了这个入口，那就退回电池优化列表页让用户自己点。
     *
     * @return true 表示已经成功把某个界面拉起来了，false 表示两条路都没走通。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnore(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (isIgnoringBatteryOptimizations(context)) return true

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(direct) }.isSuccess) return true

        return openBatteryOptimizationList(context)
    }

    /** 打开「电池优化」列表页，用户可以在里面手动找到本 App 并选择「不优化」。 */
    fun openBatteryOptimizationList(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 打开本 App 的系统详情页，国产 ROM 的省电/自启动开关通常都在这个页面的入口里。 */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 当前设备是不是那几家后台管控比较狠的 ROM。
     * 只用来决定「要不要多显示一段提示」，不做任何自动跳转 ——
     * 各家设置页的 Activity 名经常变，写死跳转反而容易闪退。
     */
    fun romHint(): String? {
        val brand = (Build.BRAND ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val name = "$brand $manufacturer"
        return when {
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                "小米/红米：设置 → 应用设置 → 应用管理 → 轻听 → 省电策略选「无限制」，" +
                    "并在「自启动」里允许。任务卡片长按锁定也有帮助。"
            name.contains("huawei") || name.contains("honor") ->
                "华为/荣耀：设置 → 电池 → 应用启动管理 → 轻听，关闭「自动管理」，" +
                    "手动勾选「允许自启动」「允许关联启动」「允许后台活动」。"
            name.contains("oppo") || name.contains("oneplus") || name.contains("realme") ->
                "OPPO/一加/realme：设置 → 电池 → 应用耗电管理 → 轻听，" +
                    "打开「允许后台运行」「允许自启动」。"
            name.contains("vivo") || name.contains("iqoo") ->
                "vivo/iQOO：设置 → 电池 → 后台高耗电 → 允许轻听；" +
                    "再在 i管家 → 应用管理 → 自启动里放行。"
            name.contains("samsung") ->
                "三星：设置 → 电池 → 后台使用限制，把轻听加入「从不休眠的应用」。"
            else -> null
        }
    }
}
