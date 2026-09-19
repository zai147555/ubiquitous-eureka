package com.nekonyan.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.ui.screen.ChatMessage
import com.nekonyan.assistant.ui.screen.ConfigScreen
import com.nekonyan.assistant.ui.screen.KnowledgeScreen
import com.nekonyan.assistant.ui.screen.LogScreen
import com.nekonyan.assistant.ui.screen.MainScreen
import com.nekonyan.assistant.ui.screen.MusicScreen
import com.nekonyan.assistant.ui.screen.NekoRoute
import com.nekonyan.assistant.ui.screen.PlaceholderScreen
import com.nekonyan.assistant.ui.screen.PluginScreen
import com.nekonyan.assistant.ui.screen.SettingsScreen
import com.nekonyan.assistant.ui.screen.TasksScreen
import com.nekonyan.assistant.ui.theme.NekoTheme
import com.nekonyan.assistant.ui.theme.ThemeViewModel
import java.util.UUID

/**
 * 应用根组件：主题 + 路由。
 *
 * 主题状态来自 [ThemeViewModel]（含系统「移除动画」与低功耗自动降级），
 * 因此这里不需要再判断任何降级逻辑。
 */
@Composable
fun NekoAppRoot() {
    val themeVm: ThemeViewModel = viewModel(factory = ThemeViewModel.Factory)
    val appearance by themeVm.settings.collectAsStateWithLifecycle()
    val motion by themeVm.effectiveMotion.collectAsStateWithLifecycle()

    NekoTheme(
        themeId = appearance.themeId,
        fontScale = appearance.fontScale,
        motionLevel = motion
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var route by remember { mutableStateOf(NekoRoute.CHAT) }

            // 聊天消息仅本地内存态；接入 DeepSeek 在 M6（届时换 ViewModel + Room 持久化）
            var messages by remember { mutableStateOf(listOf<ChatMessage>()) }

            // 聊天输入与「任务自动发送」共用同一条发送路径，避免两处逻辑漂移
            val send: (String) -> Unit = { text ->
                messages = messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    fromUser = true
                )
                NekoLog.info(NekoLog.MODULE_UI, "chat_send", "长度=${text.length}")
                // 占位回复；M6 接入 DeepSeek 后替换为真实对话
                messages = messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "（骨架）已收到。DeepSeek 对话将在 M6 接入。",
                    fromUser = false
                )
            }

            when (route) {
                NekoRoute.CHAT -> MainScreen(
                    onOpenRoute = { route = it },
                    onSend = send,
                    onEmergencyStop = {
                        // 需求：始终有紧急停止；M1 先把"停止"这个动作链路打通并留痕
                        NekoLog.warn(NekoLog.MODULE_TASK, "emergency_stop", "用户触发紧急停止")
                    },
                    messages = messages
                )

                NekoRoute.KNOWLEDGE -> KnowledgeScreen(onBack = { route = NekoRoute.CHAT })
                // 需求：点击任务自动把任务消息发给 AI —— 发送后自动切回聊天界面看结果
                NekoRoute.TASKS -> TasksScreen(
                    onBack = { route = NekoRoute.CHAT },
                    onAutoSend = send
                )
                NekoRoute.MUSIC -> MusicScreen(onBack = { route = NekoRoute.CHAT })
                NekoRoute.PLUGIN -> PluginScreen(onBack = { route = NekoRoute.CHAT })
                NekoRoute.CONFIG -> ConfigScreen(onBack = { route = NekoRoute.CHAT })
                NekoRoute.SETTINGS -> SettingsScreen(
                    onBack = { route = NekoRoute.CHAT },
                    themeVm = themeVm
                )
                NekoRoute.LOGS -> LogScreen(onBack = { route = NekoRoute.CHAT })
            }

            // 侧边菜单在非聊天页也要能打开：各功能页自带返回按钮回到聊天
            Box(Modifier.fillMaxSize()) { }
        }
    }
}
