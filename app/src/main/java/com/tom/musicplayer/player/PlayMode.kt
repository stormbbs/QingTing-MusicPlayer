package com.tom.musicplayer.player

enum class PlayMode(val label: String, val short: String) {
    SEQUENTIAL("顺序播放", "顺序"),
    REVERSE("倒序播放", "倒序"),
    SHUFFLE("随机播放", "随机"),
    REPEAT_ONE("单曲循环", "单曲");

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}
