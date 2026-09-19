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
