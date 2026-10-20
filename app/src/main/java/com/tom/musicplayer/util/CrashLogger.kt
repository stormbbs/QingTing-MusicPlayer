package com.tom.musicplayer.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器：把未捕获异常堆栈同步写入 SharedPreferences，
 * 下次启动时弹窗展示，方便在无 adb 的环境下定位问题。
 */
object CrashLogger {

    private const val FILE = "crash_logs"
    private const val KEY_LAST = "last_crash"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            runCatching {
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST, "时间: $time\n线程: ${thread.name}\n\n$stack")
                    .commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }
}
