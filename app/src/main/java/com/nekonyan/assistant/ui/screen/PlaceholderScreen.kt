package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.ui.theme.NekoTheme

/**
 * 尚未实现页面的占位。
 *
 * 刻意写清「计划在哪个里程碑做」而不是空白页 ——
 * 用户看到空页面会以为坏了，看到"将在 M2 实现"就知道是进度问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    milestone: String,
    description: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                milestone,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = NekoTheme.extra.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

// ---------------- 各功能页（按里程碑排期） ----------------

@Composable
fun PluginScreen(onBack: () -> Unit) = PlaceholderScreen(
    title = "插件",
    milestone = "计划里程碑 M15",
    description = "插件系统含 列表/已安装/已启用/已禁用/可更新/日志 六个视图；\n" +
            "插件可改所有 UI，但**不能破坏紧急停止、权限入口、日志只读与安全提示**。\n" +
            "权限需单独授权（plugin 表与权限字段已就绪）。",
    onBack = onBack
)

@Composable
fun ConfigScreen(onBack: () -> Unit) = PlaceholderScreen(
    title = "配置",
    milestone = "计划里程碑 M6 / M18",
    description = "API Base URL、Key、文本/视觉模型、超时、执行速度；\n" +
            "识别频率、本地 YOLO、Shizuku、运行模式、AI 知识库、游戏配置。\n" +
            "API Key 已支持 Keystore 加密存储（core/security/SecurityStore）。",
    onBack = onBack
)
