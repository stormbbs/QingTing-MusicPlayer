package com.tom.musicplayer.model

/** 睡眠定时的模式。 */
enum class SleepMode(val label: String) {
    OFF("不定时"),
    MINUTES("定时停止"),
    TRACKS("播完 N 首停止")
}

/** 当前定时状态；[remainingSec] 与 [remainingTracks] 只在对应模式下有意义。 */
data class SleepState(
    val mode: SleepMode = SleepMode.OFF,
    val remainingSec: Long = 0L,
    val remainingTracks: Int = 0
) {
    val active: Boolean get() = mode != SleepMode.OFF
}
