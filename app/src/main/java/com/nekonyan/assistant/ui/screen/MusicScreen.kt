package com.nekonyan.assistant.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.data.repo.MusicRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * 音乐页（`修改.ds` 第四项）。
 *
 * 新增：**导入音乐**（系统选择器多选 → 复制进私有目录 → 解析标题/艺术家/专辑/时长/封面 → 去重 → 日志）、
 * 播放/暂停/上一首/下一首、删除。
 *
 * 仍然坚持需求里反复强调的三条：**仅播放已下载、无在线搜索与推荐、不自动播放**
 * （不会自动下一首：播放器只挂一个 MediaItem，切换曲目必须是用户动作）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MusicRepository(ctx) }
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf<List<MusicRepository.Track>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    // 播放状态来自共享播放器：agent 让它放歌，这个页面也会立刻反映出来
    val playingPath by com.nekonyan.assistant.core.music.MusicPlayer.current.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    // 播放器由 MusicPlayer 单例持有，**这里刻意不 release**：
    // 共享实例一旦随页面销毁释放，用户刚让猫娘放的歌一退出页面就断了。

    fun rescan() {
        scope.launch {
            scanning = true
            tracks = repo.scanLocal()
            scanning = false
        }
    }

    // 首帧扫一次本地（只扫本地目录，不联网）
    LaunchedEffect(Unit) { rescan() }

    fun play(track: MusicRepository.Track) {
        scope.launch {
            com.nekonyan.assistant.core.music.MusicPlayer.toggle(ctx, track.path, track.displayName)
        }
    }

    /** 上一首/下一首：用户主动点了才切（不自动续播） */
    fun step(delta: Int) {
        if (tracks.isEmpty()) return
        val current = tracks.indexOfFirst { it.path == playingPath }
        val next = when {
            current < 0 -> 0
            else -> (current + delta + tracks.size) % tracks.size
        }
        val target = tracks[next]
        scope.launch {
            com.nekonyan.assistant.core.music.MusicPlayer.play(ctx, target.path, target.displayName)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val outcome = repo.importFiles(uris)
                message = outcome.summary
                tracks = repo.scanLocal()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 需求：顶部「导入音乐」，系统文件选择器多选
                    IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = "导入音乐")
                    }
                    IconButton(onClick = { rescan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描本地")
                    }
                }
            )
        },
        bottomBar = {
            if (playingPath != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { step(-1) }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                        }
                        IconButton(onClick = { playingPath?.let { p -> tracks.firstOrNull { it.path == p }?.let { play(it) } } }) {
                            Icon(Icons.Filled.Pause, contentDescription = "暂停")
                        }
                        IconButton(onClick = { step(1) }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "仅播放已下载的本地音乐：无在线搜索、无推荐、不会自动播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )

            message?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { message = null }) { Text("关闭") }
                    }
                }
            }

            HorizontalDivider()

            if (tracks.isEmpty()) {
                Text(
                    if (scanning) "正在扫描本地音乐…" else "还没有本地音乐：点右上角「导入音乐」从文件里选（支持 mp3/flac/wav/aac/m4a/ogg）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id }) { t ->
                        TrackRow(
                            track = t,
                            playing = playingPath == t.path,
                            onPlay = { play(t) },
                            onDelete = {
                                scope.launch {
                                    if (playingPath == t.path) {
                                        // 必须先停再删（顺序不能反、也不能另起协程）：
                                        // 否则解码器还抓着已删除的文件
                                        com.nekonyan.assistant.core.music.MusicPlayer.stop()
                                    }
                                    val ok = repo.delete(t)
                                    message = if (ok) "已删除「${t.displayName}」" else "删除失败：${t.displayName}"
                                    tracks = repo.scanLocal()
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicRepository.Track,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (track.subtitle.isNotBlank()) {
                    Text(
                        track.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    "${MusicRepository.formatDuration(track.durationMs)} · ${track.sizeBytes / 1024 / 1024} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}
