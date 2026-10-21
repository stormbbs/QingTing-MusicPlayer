package com.tom.musicplayer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.tom.musicplayer.model.OnlinePlaylist
import com.tom.musicplayer.model.OnlineSong

/**
 * 在线页的「记忆」。
 *
 * 之前这些都是 OnlineScreen 里的 remember：一按底栏去「我的音乐」，在线页就从组合里
 * 撤掉了，回来时状态全没 —— 明明刚才还在榜单里点「播放全部」，回来却只剩一个空的
 * 搜索框，得重新搜一遍、重新点进榜单。切个页面不等于放弃刚才的浏览。
 *
 * 状态提到外面存着，页面重新进入时直接接上：哪个标签、搜了什么、点进了哪个榜单，
 * 连列表滚到哪儿都还在。
 *
 * 放 object 而不是 ViewModel：三个主页是同一个 Activity 里的平级页面，没有各自
 * 独立的作用域；object 的生命周期跟进程走，也不用处理「谁持有谁」。
 */
internal object OnlineUi {

    val query = mutableStateOf("")

    val results = mutableStateOf<List<OnlineSong>>(emptyList())

    val playlists = mutableStateOf<List<OnlinePlaylist>>(emptyList())

    /** 榜单拉过一次就不必每次进页面都重拉；下拉刷新或切标签时再刷。 */
    val playlistsLoaded = mutableStateOf(false)

    /** 0 = 搜索结果，1 = 榜单歌单。 */
    val tab = mutableIntStateOf(0)

    /** 正在看的榜单/歌单：名字 + 曲目。 */
    val detail = mutableStateOf<Pair<String, List<OnlineSong>>?>(null)

    val message = mutableStateOf<String?>(null)

    val resultsScroll = LazyListState()
    val chartsScroll = LazyListState()
    val detailScroll = LazyListState()

    /** 用户自己清空搜索时才真的丢掉浏览现场。 */
    fun clearResults() {
        results.value = emptyList()
        message.value = null
        detail.value = null
    }
}
