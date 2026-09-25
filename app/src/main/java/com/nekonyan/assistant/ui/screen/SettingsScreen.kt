package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.core.env.ThermalMonitor
import com.nekonyan.assistant.ui.theme.MotionLevel
import com.nekonyan.assistant.ui.theme.NekoTheme
import com.nekonyan.assistant.ui.theme.NekoThemeId
import com.nekonyan.assistant.ui.theme.ThemeViewModel
import androidx.compose.material.icons.filled.Security

/**
 * 设置页（需求相关项）：
 *   · 主题：橘猫 / 白猫 / 黑猫 / 高对比度（默认橘猫色）
 *   · 字体大小可调、高级动态效果可一键关闭、支持「减少动态效果」
 *   · 色盲友好：不只靠颜色（所有开关都带文字状态）
 *   · 实时温度与电量（需求：支持用户查看实时温度和功耗）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeVm: ThemeViewModel = viewModel(factory = ThemeViewModel.Factory),
    /** 需求（修改.ds 第五项）：稍后可从设置里重新进入权限引导 */
    onOpenPermissionGuide: () -> Unit = {}
) {
    val appearance by themeVm.settings.collectAsStateWithLifecycle()
    val motion by themeVm.effectiveMotion.collectAsStateWithLifecycle()
    val thermal by ThermalMonitor.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                actions = {
                    IconButton(onClick = onOpenPermissionGuide) {
                        Icon(Icons.Filled.Security, contentDescription = "权限引导")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------------- 外观 ----------------
            GroupTitle("外观")

            SettingBlock("主题配色") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NekoThemeId.entries.forEach { id ->
                        FilterChip(
                            selected = appearance.themeId == id,
                            onClick = { themeVm.setTheme(id) },
                            label = { Text(id.label) }
                        )
                    }
                }
                Text(
                    "当前：${appearance.themeId.label}（默认橘猫色）",
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted
                )
            }

            SettingBlock("字体大小：${(appearance.fontScale * 100).toInt()}%") {
                Slider(
                    value = appearance.fontScale,
                    onValueChange = { themeVm.setFontScale(it) },
                    valueRange = 0.8f..1.6f,
                    steps = 7
                )
            }

            SettingBlock("高级动态效果") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MotionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = appearance.motionLevel == level,
                            onClick = { themeVm.setMotion(level) },
                            label = { Text(level.label) }
                        )
                    }
                }
                Text(
                    "实际生效：${motion.label}" +
                            if (motion != appearance.motionLevel) {
                                "（因系统「移除动画」或功耗/发热自动降级）"
                            } else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted
                )
            }

            // ---------------- 能耗 ----------------
            GroupTitle("功耗与发热")
            SettingBlock("自动降级") {
                SwitchRow(
                    title = "低功耗 / 发热 / 低电量时自动降级",
                    checked = appearance.autoDegrade,
                    onCheckedChange = { themeVm.setAutoDegrade(it) }
                )
                Text(
                    "温度分级：<38℃ 全速；38~42℃ 降频；42~45℃ 仅核心识别；>45℃ 暂停非关键任务\n" +
                            "电量分级：<30% 降频；<20% 只提示不操作；<10% 停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted
                )
            }

            SettingBlock("实时状态") {
                InfoRow("温度", thermal.celsius?.let { "%.1f℃".format(it) } ?: "未知（本机未暴露热区）")
                InfoRow("电量", "${thermal.batteryPercent}%" + if (thermal.charging) " • 充电中" else "")
                InfoRow("性能档位", thermal.perfLevel.label)
            }

            // ---------------- 无障碍 ----------------
            GroupTitle("无障碍与可访问性")
            SettingBlock("可访问性") {
                SwitchRow(
                    title = "视障模式（大字体 + 语音导航）",
                    checked = appearance.accessibilityMode,
                    onCheckedChange = { themeVm.setAccessibilityMode(it) }
                )
                SwitchRow(
                    title = "色盲友好（信息不只靠颜色）",
                    checked = appearance.colorBlindFriendly,
                    onCheckedChange = { themeVm.setColorBlindFriendly(it) }
                )
            }

            // ---------------- 悬浮窗 ----------------
            GroupTitle("悬浮窗")
            SettingBlock("悬浮窗聊天") {
                val ovCtx = androidx.compose.ui.platform.LocalContext.current
                val overlayOn by com.nekonyan.assistant.core.overlay.OverlayChatService
                    .runningFlow.collectAsStateWithLifecycle()
                SwitchRow(
                    title = "在其他 App 上显示悬浮聊天窗",
                    checked = overlayOn,
                    onCheckedChange = { on ->
                        when {
                            !on -> com.nekonyan.assistant.core.overlay.OverlayChatService.hide(ovCtx)
                            !com.nekonyan.assistant.core.overlay.OverlayChatService.canDraw(ovCtx) ->
                                runCatching {
                                    ovCtx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${ovCtx.packageName}")
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            else -> com.nekonyan.assistant.core.overlay.OverlayChatService.show(ovCtx)
                        }
                    }
                )
                Text(
                    "开启后可在其他 App 上层显示一个可拖动气泡，点开随时聊天（聊天记录与 App 内是同一份）；" +
                        "首次开启会先要「显示在其他应用上层」权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // 悬浮窗依赖系统侧开关，失败时必须让用户看到原因（否则就是"点了没反应"）
                val ovError by com.nekonyan.assistant.core.overlay.OverlayChatService
                    .lastError.collectAsStateWithLifecycle()
                ovError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                var checks by remember {
                    mutableStateOf<List<Pair<String, Boolean>>?>(null)
                }
                TextButton(onClick = {
                    checks = com.nekonyan.assistant.core.overlay.OverlayChatService.selfCheck(ovCtx)
                }) { Text("悬浮窗自检") }
                checks?.let { list ->
                    list.forEach { (name, ok) ->
                        Text(
                            (if (ok) "✓ " else "✗ ") + name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---------------- 语音朗读 ----------------
            GroupTitle("语音朗读")
            SettingBlock("朗读音色") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val store = remember { com.nekonyan.assistant.data.repo.VoiceSettingsStore(ctx) }
                var voiceSettings by remember { mutableStateOf(store.load()) }
                var autoSpeak by remember { mutableStateOf(store.autoSpeak()) }
                var showVoiceDialog by remember { mutableStateOf(false) }
                SwitchRow(
                    title = "自动朗读回复（收到回复就自动播放语音）",
                    checked = autoSpeak,
                    onCheckedChange = { on ->
                        autoSpeak = on
                        store.setAutoSpeak(on)
                    }
                )
                SwitchRow(
                    title = "用微软官方语音（免部署，推荐）",
                    checked = voiceSettings.edgeEnabled,
                    onCheckedChange = { on ->
                        voiceSettings = voiceSettings.copy(edgeEnabled = on)
                        store.saveEdge(on, voiceSettings.edgeVoice, voiceSettings.edgeRate, voiceSettings.edgePitch)
                    }
                )

                Text(
                    "当前音色：" + (com.nekonyan.assistant.core.voice.EdgeTtsClient.FALLBACK_VOICES
                        .firstOrNull { it.shortName == voiceSettings.edgeVoice }?.friendlyName
                        ?: voiceSettings.edgeVoice) + "（语速 ${voiceSettings.edgeRate}）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // 快捷更换：常用音色一点即换，不用进对话框
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.nekonyan.assistant.core.voice.EdgeTtsClient.FALLBACK_VOICES.take(4).forEach { v ->
                        FilterChip(
                            selected = voiceSettings.edgeVoice == v.shortName,
                            onClick = {
                                voiceSettings = voiceSettings.copy(edgeVoice = v.shortName)
                                store.saveEdge(
                                    voiceSettings.edgeEnabled, v.shortName,
                                    voiceSettings.edgeRate, voiceSettings.edgePitch
                                )
                            },
                            label = { Text(v.friendlyName.substringBefore(" ·")) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "换音色 / 调语速 / 试听（官方共 300+ 个音色）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showVoiceDialog = true }) { Text("更换音色") }
                }
                if (showVoiceDialog) {
                    com.nekonyan.assistant.ui.component.VoiceSettingsDialog(
                        initial = voiceSettings,
                        onDismiss = { showVoiceDialog = false },
                        onSave = { s ->
                            voiceSettings = s
                            store.saveEdge(s.edgeEnabled, s.edgeVoice, s.edgeRate, s.edgePitch)
                            showVoiceDialog = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 4.dp)
    )
    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
}

@Composable
private fun SettingBlock(label: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        // 需求：色盲友好 —— 开关旁边永远有文字状态，不依赖颜色
        Text(
            if (checked) "开" else "关",
            style = MaterialTheme.typography.labelSmall,
            color = NekoTheme.extra.muted
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall,
            color = NekoTheme.extra.muted)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}
