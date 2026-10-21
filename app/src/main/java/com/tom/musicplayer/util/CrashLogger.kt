package com.tom.musicplayer.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃与异常退出记录器。
 *
 * 两类「App 没了」要分开记，否则事后无从判断：
 *
 *  - **Java 崩溃**：走到未捕获异常，能拿到完整堆栈（[read]）。
 *  - **被系统收掉**：进程被 LMK / ROM 后台管控直接杀掉，不会有任何异常，
 *    只在下次服务被拉起时才能看出端倪 —— 上一次的「服务存活标记」没被清掉，
 *    说明它没走 onDestroy（[lastKilledAt]）。
 *
 * 诊断信息同步写 SharedPreferences，下次启动弹窗展示，无 adb 环境也能查。
 */
object CrashLogger {

    private const val FILE = "crash_logs"
    private const val KEY_LAST = "last_crash"
    private const val KEY_SERVICE_SINCE = "service_since"
    private const val KEY_LAST_KILLED = "last_killed"

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            runCatching {
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST, "时间: ${now()}\n线程: ${thread.name}\n\n$stack")
                    .commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 播放服务每次起来时调用。
     *
     * 如果上一次留下的存活标记还在，说明上次这个服务没走 onDestroy 就消失了 ——
     * 进程被系统直接回收（内存紧张、ROM 清理、用户强行停止）。
     * 这条信息不含堆栈，但足以把「崩溃」和「被杀」区分开。
     */
    fun markServiceStart(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val since = prefs.getString(KEY_SERVICE_SINCE, null)
            val edit = prefs.edit()
            if (since != null) edit.putString(KEY_LAST_KILLED, since)
            edit.putString(KEY_SERVICE_SINCE, now()).commit()
        }
    }

    /** 服务正常销毁（stopSelf / 用户主动停止）时清掉标记，下次不算异常退出。 */
    fun markServiceStop(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().remove(KEY_SERVICE_SINCE).commit()
        }
    }

    /** 上一次播放服务是被系统直接收掉的时间；正常情况返回 null。 */
    fun lastKilledAt(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST_KILLED, null)

    fun read(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }
}
