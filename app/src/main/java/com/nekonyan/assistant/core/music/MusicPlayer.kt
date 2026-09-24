package com.nekonyan.assistant.core.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 共享播放器：**音乐页与 agent 工具用同一个 ExoPlayer**。
 *
 * 为什么必须共享：各自 `ExoPlayer.Builder().build()` 会变成两路独立音频 ——
 * 猫娘放一首、用户再点一首，两首歌同时响；而且 agent 播了什么，音乐页完全不知道。
 *
 * ExoPlayer 必须在带 Looper 的线程上访问，所以公开方法内部统一切到 Main；
 * 调用方可以从任意协程调用（工具层就跑在 IO 上）。
 *
 * 刻意不提供"自动下一首"：需求里写死了不自动续播，切歌只能是用户动作或一次明确的工具调用。
 */
object MusicPlayer {

    @Volatile private var player: ExoPlayer? = null

    private val _current = MutableStateFlow<String?>(null)

    /** 当前挂着的曲目路径（null = 没挂任何曲目）。界面据此高亮与显示底部控制条。 */
    val current: StateFlow<String?> = _current.asStateFlow()

    @Volatile var currentTitle: String? = null
        private set

    private fun playerOf(context: Context): ExoPlayer =
        player ?: ExoPlayer.Builder(context.applicationContext).build().also { player = it }

    /** 播放指定文件（同一个文件重复调用就是从头再放，不做"暂停/续播"判断） */
    suspend fun play(context: Context, path: String, title: String?) = withContext(Dispatchers.Main) {
        val p = playerOf(context)
        if (!File(path).isFile) return@withContext
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        p.prepare()
        p.play()
        currentTitle = title
        _current.value = path
    }

    /** 点同一首 = 暂停并摘掉；点别的 = 换歌播放。返回 true 表示现在在放 */
    suspend fun toggle(context: Context, path: String, title: String?): Boolean =
        withContext(Dispatchers.Main) {
            val p = playerOf(context)
            if (_current.value == path && p.isPlaying) {
                p.pause()
                false
            } else {
                if (!File(path).isFile) return@withContext false
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                p.prepare()
                p.play()
                currentTitle = title
                _current.value = path
                true
            }
        }

    suspend fun pause() = withContext(Dispatchers.Main) { player?.pause() }

    /** 停止并摘掉当前曲目（删除正在放的文件时必须调用，否则解码器还抓着已删的文件） */
    suspend fun stop() = withContext(Dispatchers.Main) {
        player?.stop()
        _current.value = null
        currentTitle = null
    }

    suspend fun isPlaying(): Boolean = withContext(Dispatchers.Main) { player?.isPlaying == true }
}
