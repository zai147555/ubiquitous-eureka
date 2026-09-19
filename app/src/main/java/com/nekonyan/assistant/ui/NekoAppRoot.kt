package com.nekonyan.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.ui.screen.ChatViewModel
import com.nekonyan.assistant.ui.screen.ConfigScreen
import com.nekonyan.assistant.ui.screen.KnowledgeScreen
import com.nekonyan.assistant.ui.screen.LogScreen
import com.nekonyan.assistant.ui.screen.MainScreen
import com.nekonyan.assistant.ui.screen.MusicScreen
import com.nekonyan.assistant.ui.screen.NekoRoute
import com.nekonyan.assistant.ui.screen.PluginScreen
import com.nekonyan.assistant.ui.screen.SettingsScreen
import com.nekonyan.assistant.ui.screen.TasksScreen
import com.nekonyan.assistant.ui.theme.NekoTheme
import com.nekonyan.assistant.ui.theme.ThemeViewModel

/**
 * 应用根组件：主题 + 路由 + 聊天状态。
 *
 * 聊天状态刻意提升到这里（而不是放在 [MainScreen] 内部）：
 *   · 切到知识库/配置再回来，正在进行的回复不会断；
 *   · 任务页「一键执行」自动发送的路径与手动输入**共用同一个 ViewModel**，
 *     避免两处发送逻辑各写一遍然后慢慢漂移。
 */
@Composable
fun NekoAppRoot() {
    val themeVm: ThemeViewModel = viewModel(factory = ThemeViewModel.Factory)
    val appearance by themeVm.settings.collectAsStateWithLifecycle()
    val motion by themeVm.effectiveMotion.collectAsStateWithLifecycle()

    val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
    val chatState by chatVm.state.collectAsStateWithLifecycle()

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
            var backToDrawer by remember { mutableStateOf(false) }

            // 需求：从设置等页面退出 → 回聊天页并展开侧边栏（侧边栏是唯一导航入口，
            // 回到一个"什么都没有"的聊天页会逼用户再点一次三条杠）
            val backToChat: () -> Unit = {
                backToDrawer = true
                route = NekoRoute.CHAT
            }

            // 系统返回键在所有功能页都走同一条"回侧边栏"路径
            BackHandler(enabled = route != NekoRoute.CHAT) { backToChat() }

            when (route) {
                NekoRoute.CHAT -> {
                    // 从配置页回来时刷新「是否已配 Key」，让提示及时消失
                    LaunchedEffect(Unit) { chatVm.refreshConfigFlag() }

                    MainScreen(
                        onOpenRoute = { route = it },
                        onSend = chatVm::send,
                        // 需求：始终有紧急停止 —— 这里真的会切断网络请求，不只是改个状态
                        onEmergencyStop = { chatVm.stop() },
                        runningTaskLabel = if (chatState.streaming) "猫娘助手正在回复" else null,
                        messages = chatState.messages,
                        streamingText = chatState.streamingText,
                        errorText = chatState.error,
                        onDismissError = { chatVm.clearError() },
                        startWithDrawerOpen = backToDrawer,
                        onDrawerOpened = { backToDrawer = false }
                    )
                }

                NekoRoute.KNOWLEDGE -> KnowledgeScreen(onBack = backToChat)

                // 需求：点击任务自动把任务消息发给 AI —— 与手动输入走同一条发送路径
                NekoRoute.TASKS -> TasksScreen(
                    onBack = backToChat,
                    onAutoSend = chatVm::send
                )

                NekoRoute.MUSIC -> MusicScreen(onBack = backToChat)
                NekoRoute.PLUGIN -> PluginScreen(onBack = backToChat)
                NekoRoute.CONFIG -> ConfigScreen(onBack = backToChat)
                NekoRoute.SETTINGS -> SettingsScreen(
                    onBack = backToChat,
                    themeVm = themeVm
                )
                NekoRoute.LOGS -> LogScreen(onBack = backToChat)
            }
        }
    }
}
