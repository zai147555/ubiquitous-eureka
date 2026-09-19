package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.nekonyan.assistant.data.repo.MusicRepository
import com.nekonyan.assistant.ui.theme.NekoTheme
import kotlinx.coroutines.launch

/**
 * 音乐页（需求原文，重复强调三次）：
 *   「音乐播放：仅本地已下载，无推荐、无自动播放」
 *
 * 因此这个界面**只有**「扫描本地 / 列表 / 手动点播」三件事：
 *   · 没有任何推荐位、没有"猜你喜欢"、没有自动播放队列；
 *   · 打开页面不会自动播放（必须用户点某一行）；
 *   · 扫描只在用户点刷新或进入页面时执行本地文件遍历，绝不联网。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MusicRepository(ctx) }
    var tracks by remember { mutableStateOf<List<MusicRepository.Track>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var playingPath by remember { mutableStateOf<String?>(null) }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    val scope = rememberCoroutineScope()

    // 需求：扫描只遍历本地文件，绝不联网；打开页面只扫一次
    val rescan: () -> Unit = {
        scope.launch {
            scanning = true
            tracks = repo.scanLocal()
            scanning = false
        }
    }

    LaunchedEffect(Unit) { rescan() }

    DisposableEffect(Unit) {
        onDispose { player.release() }
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
                    IconButton(onClick = rescan) {
                        // 手动刷新；不做定时轮询，避免无谓耗电
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描本地音乐")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 明确告知用户"只有本地的" —— 避免以为功能坏了
            Text(
                "仅播放本地已下载音乐 · 无推荐 · 不会自动播放",
                style = MaterialTheme.typography.labelSmall,
                color = NekoTheme.extra.muted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )

            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = NekoTheme.extra.muted
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (scanning) "正在扫描本地音乐…" else "没有找到本地音乐",
                            color = NekoTheme.extra.muted
                        )
                        Text(
                            "把音频文件放到：\n" +
                                    "Android/data/com.nekonyan.assistant/files/music/\n" +
                                    "或应用私有目录 files/music/",
                            style = MaterialTheme.typography.labelSmall,
                            color = NekoTheme.extra.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks, key = { it.id }) { t ->
                        TrackRow(
                            track = t,
                            playing = playingPath == t.path,
                            onPlay = {
                                // 需求：手动播放 —— 只播这一首，不排队、不自动下一首
                                if (playingPath == t.path) {
                                    player.pause()
                                    playingPath = null
                                } else {
                                    player.setMediaItem(MediaItem.fromUri("file://${t.path}"))
                                    player.prepare()
                                    player.play()
                                    playingPath = t.path
                                }
                            }
                        )
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
    onPlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        track.artist?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                        append(MusicRepository.formatDuration(track.durationMs))
                        append(" · ")
                        append("%.1f MB".format(track.sizeBytes / 1024.0 / 1024.0))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onPlay) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
