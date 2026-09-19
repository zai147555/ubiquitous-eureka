package com.nekonyan.assistant.data.repo

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地音乐仓库（需求原文，反复强调三次）：
 *   「音乐仅播放已下载，无推荐、无自动播放」
 *
 * 因此这个仓库**故意**不提供任何网络检索 API，也没有"猜你喜欢"之类的接口 ——
 * 从类型层面保证做不出推荐功能。
 *
 * 扫描范围：应用私有目录下的 music/ 与系统音乐目录（需用户授予媒体权限）。
 */
class MusicRepository(private val context: Context) {

    data class Track(
        val id: String,
        val title: String,
        val artist: String?,
        val durationMs: Long,
        val path: String,
        val sizeBytes: Long
    ) {
        /** 需求：列表展示用，避免超长文件名撑破布局 */
        val displayName: String get() = title.ifBlank { File(path).name }
    }

    /** 需求：只列本地已下载的文件；扫不到就返回空列表，绝不联网找 */
    suspend fun scanLocal(): List<Track> = withContext(Dispatchers.IO) {
        val dirs = buildList {
            add(File(context.filesDir, "music"))
            add(File(context.getExternalFilesDir(null), "music"))
        }
        val out = ArrayList<Track>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles { f -> f.isFile && isAudio(f.name) }?.forEach { f ->
                out.add(readMeta(f))
            }
        }
        out.sortedBy { it.displayName }
    }

    private fun readMeta(f: File): Track {
        var title = f.nameWithoutExtension
        var artist: String? = null
        var duration = 0L
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { duration = it }
            }
        }
        return Track(
            id = f.absolutePath.hashCode().toString(),
            title = title,
            artist = artist,
            durationMs = duration,
            path = f.absolutePath,
            sizeBytes = f.length()
        )
    }

    companion object {
        /** 需求：仅本地音频；扩展名判定抽成公开纯函数，便于单元测试覆盖 */
        fun isAudio(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") ||
                    n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac")
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "--:--"
            val total = ms / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}
