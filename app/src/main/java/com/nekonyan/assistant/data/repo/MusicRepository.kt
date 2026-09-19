package com.nekonyan.assistant.data.repo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.nekonyan.assistant.core.log.NekoLog
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
 * `修改.ds` 第四项新增：导入（系统选择器多选 → 复制进私有目录 → 解析元数据 → 去重 → 日志）、
 * 删除。**仍然没有**在线搜索 / 推荐 / 自动播放 / 自动下一首。
 */
class MusicRepository(private val context: Context) {

    data class Track(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val path: String,
        val sizeBytes: Long,
        /** 内嵌封面导出到私有目录后的路径（没有封面则为 null） */
        val coverPath: String? = null
    ) {
        /** 需求：列表展示用，避免超长文件名撑破布局 */
        val displayName: String get() = title.ifBlank { File(path).name }

        /** 副标题：艺术家 · 专辑（都没有就留空，不显示占位符） */
        val subtitle: String
            get() = listOfNotNull(
                artist?.takeIf { it.isNotBlank() },
                album?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
    }

    /** 导入结果：成功与被跳过（重复 / 格式不支持 / 打不开）分开报，界面直接显示 */
    data class ImportOutcome(
        val imported: List<String> = emptyList(),
        val skipped: List<String> = emptyList()
    ) {
        val summary: String
            get() = buildString {
                append("导入 ${imported.size} 首")
                if (skipped.isNotEmpty()) append("，跳过 ${skipped.size} 个：").append(skipped.joinToString("、"))
            }
    }

    private val musicDir: File get() = File(context.filesDir, "music")
    private val coverDir: File get() = File(musicDir, "covers")

    /** 需求：只列本地已下载的文件；扫不到就返回空列表，绝不联网找 */
    suspend fun scanLocal(): List<Track> = withContext(Dispatchers.IO) {
        val dirs = buildList {
            add(musicDir)
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

    // ---------------- 导入（修改.ds 第四项） ----------------

    /**
     * 从系统选择器拿到的 URI 导入：复制到应用私有目录，保证长期可读
     * （比持久化 URI 权限更稳：卸载前一直在，也不依赖授权是否被回收）。
     */
    suspend fun importFiles(uris: List<Uri>): ImportOutcome = withContext(Dispatchers.IO) {
        musicDir.mkdirs()
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        uris.forEach { uri ->
            val name = displayName(uri)
            if (name.isNullOrBlank()) { skipped += "未知文件"; return@forEach }
            if (!isAudio(name)) { skipped += "$name（格式不支持）"; return@forEach }

            val target = File(musicDir, name)
            if (target.exists() && target.length() > 0) {
                skipped += "$name（已存在）"
                return@forEach
            }

            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: error("打开输入流失败")
            }.isSuccess

            if (!ok || target.length() == 0L) {
                target.delete()
                skipped += "$name（读取失败）"
                return@forEach
            }

            // 解析元数据（导入时做一次，列表就不用每次读文件）
            readMeta(target)
            imported += name
            NekoLog.info(NekoLog.MODULE_STORE, "music_import", "$name ${target.length() / 1024}KB")
        }
        ImportOutcome(imported, skipped)
    }

    /** 需求：支持删除已导入音乐 */
    suspend fun delete(track: Track): Boolean = withContext(Dispatchers.IO) {
        val file = File(track.path)
        val deleted = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        track.coverPath?.let { runCatching { File(it).delete() } }
        if (deleted) NekoLog.info(NekoLog.MODULE_STORE, "music_delete", track.displayName)
        deleted
    }

    // ---------------- 内部 ----------------

    private fun readMeta(f: File): Track {
        var title = f.nameWithoutExtension
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var cover: String? = null
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { duration = it }
                cover = saveCover(r.embeddedPicture, f)
            }
        }
        return Track(
            id = f.absolutePath.hashCode().toString(),
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            path = f.absolutePath,
            sizeBytes = f.length(),
            coverPath = cover
        )
    }

    /** 内嵌封面写进私有目录（同名 mp3 重导入会覆盖，不会堆垃圾图） */
    private fun saveCover(bytes: ByteArray?, source: File): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            coverDir.mkdirs()
            val out = File(coverDir, source.nameWithoutExtension + ".jpg")
            out.outputStream().use { it.write(bytes) }
            out.absolutePath
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment
    }.getOrNull()

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
