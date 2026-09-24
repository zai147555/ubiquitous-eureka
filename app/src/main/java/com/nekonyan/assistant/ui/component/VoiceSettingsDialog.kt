package com.nekonyan.assistant.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.core.voice.EdgeTtsClient
import com.nekonyan.assistant.core.voice.VoicePipeline
import com.nekonyan.assistant.data.repo.VoiceSettings
import kotlinx.coroutines.launch

/**
 * 语音设置：音色（**从微软官方列表拉取**）、语速、试听。
 *
 * 刻意不放"服务地址"输入框 —— 官方语音是端上直连公共服务，用户不需要部署任何东西。
 * 拉不到官方列表时回退到内置常用音色，并**如实标注**是回退结果，而不是假装列表是完整的。
 *
 * RVC 变声不在这里配置：它只作用于系统 TTS 那一档（RVC 要 WAV，官方语音给 MP3）。
 */
@Composable
fun VoiceSettingsDialog(
    initial: VoiceSettings,
    onDismiss: () -> Unit,
    onSave: (VoiceSettings) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { VoicePipeline(ctx) }
    DisposableEffect(Unit) { onDispose { pipeline.release() } }

    var current by remember { mutableStateOf(initial) }
    var voices by remember { mutableStateOf<List<EdgeTtsClient.Voice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fromFallback by remember { mutableStateOf(false) }
    var previewMsg by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fetched = runCatching { EdgeTtsClient().voices() }.getOrDefault(emptyList())
        val zh = fetched.filter { it.locale.startsWith("zh") }
        if (zh.isEmpty()) {
            voices = EdgeTtsClient.FALLBACK_VOICES
            fromFallback = true
        } else {
            // 中文音色优先展示，其后是其它语言（都用官方 FriendlyName）
            voices = zh + fetched.filterNot { it.locale.startsWith("zh") }
        }
        loading = false
    }

    val rates = listOf("-30%" to "慢", "-15%" to "稍慢", "+0%" to "正常", "+20%" to "稍快", "+40%" to "快")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音朗读") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "用微软官方语音（端上直连，免部署）",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = current.edgeEnabled,
                        onCheckedChange = { current = current.copy(edgeEnabled = it) }
                    )
                    Text(
                        if (current.edgeEnabled) "已开启：优先用官方语音，失败自动回退系统 TTS"
                        else "已关闭：只用设备自带的系统 TTS",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Text(
                    "语速",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rates.forEach { (value, label) ->
                        TextButton(onClick = { current = current.copy(edgeRate = value) }) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (current.edgeRate == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    "音色" + when {
                        loading -> "（正在获取官方列表…）"
                        fromFallback -> "（拉取失败，下面是内置常用音色）"
                        else -> "（官方列表，共 ${voices.size} 个）"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else {
                    voices.forEach { v ->
                        val selected = v.shortName == current.edgeVoice
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = current.copy(edgeVoice = v.shortName) }
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(selected = selected, onClick = { current = current.copy(edgeVoice = v.shortName) })
                            Column {
                                Text(v.friendlyName.ifBlank { v.shortName }, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    v.shortName + if (v.gender.isNotBlank()) " · ${if (v.gender == "Female") "女声" else "男声"}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                TextButton(
                    enabled = !previewing,
                    onClick = {
                        previewing = true
                        previewMsg = "正在合成…"
                        scope.launch {
                            // 走真实链路（含回退），返回的是人话结果，直接显示给用户
                            previewMsg = pipeline.speak("你好，我是猫娘助手，现在这样说话好听吗？", current)
                            previewing = false
                        }
                    }
                ) { Text(if (previewing) "试听中…" else "试听（用当前音色）") }
                previewMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
