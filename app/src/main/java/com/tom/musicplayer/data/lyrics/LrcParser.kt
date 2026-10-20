package com.tom.musicplayer.data.lyrics

import com.tom.musicplayer.model.LyricLine

/**
 * LRC 歌词解析器。
 *
 * 支持常见写法：
 * - `[mm:ss.xx]文本`
 * - 一行多个时间戳 `[00:12.00][01:20.00]same text`
 * - 头部的 `[ti:xxx]` `[ar:xxx]` `[al:xxx]` 元信息标签会被忽略
 * - `[offset:+/-毫秒]`：整体时间偏移
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val tags = TIME_TAG.findAll(trimmed).toList()
            if (tags.isEmpty()) continue
            val text = trimmed.substring(tags.last().range.last + 1).trim()
            // 只有时间轴没有内容的多是间奏占位，保留空行会让界面抖动，直接跳过
            if (text.isBlank()) continue
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    3 -> fraction.toLong()
                    else -> 0L
                }
                out.add(LyricLine(minutes * 60_000L + seconds * 1_000L + millis, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 读取 LRC 里自带的 offset 标签；没有就返回 0。 */
    fun parseOffset(raw: String): Long {
        val match = OFFSET_TAG.find(raw) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    /** 纯文本没有时间轴时，按行生成「带 0 偏移」的行，至少能滚动显示内容。 */
    fun plainLines(raw: String): List<LyricLine> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .mapIndexed { index, text -> LyricLine(index * 3_000L, text) }
            .toList()
}
