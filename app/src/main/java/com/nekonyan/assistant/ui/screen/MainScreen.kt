package com.nekonyan.assistant.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.ui.component.EmergencyStopButton
import com.nekonyan.assistant.ui.component.NekoDrawerContent
import com.nekonyan.assistant.ui.theme.NekoTheme
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.VolumeUp

/**
 * 主界面（需求原文）：
 *   · **QQ 风格，顶部无返回键和聊天名**；
 *   · 底部：输入框、发送、导入文件、照片；
 *   · **右上三条杠**展开侧边菜单（知识库、任务、音乐、插件、配置、设置、全部日志）；
 *   · **始终有紧急停止按钮**（音量键、通知栏也可停止）。
 *
 * 说明：主界面刻意不放返回键与标题 —— 侧边菜单是唯一导航入口，
 * 这与需求一致，也避免了「标题挤占聊天区」的 QQ 布局问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenRoute: (NekoRoute) -> Unit,
    onSend: (String) -> Unit = {},
    onEmergencyStop: () -> Unit = {},
    runningTaskLabel: String? = null,
    messages: List<ChatMessage> = emptyList(),
    /** 正在流式接收的回复（还没落库，边收边显示） */
    streamingText: String = "",
    errorText: String? = null,
    onDismissError: () -> Unit = {},
    /** 导入文件/照片：参数为 (uri, 是否图片) */
    onAttachment: (android.net.Uri, Boolean) -> Unit = { _, _ -> },
    /** 朗读某条消息（TTS + 可选 RVC 变声） */
    onSpeak: (String) -> Unit = {},
    /** 右上角 ＋：新建对话 / 管理对话 */
    sessions: List<com.nekonyan.assistant.data.db.ConversationSession> = emptyList(),
    currentSessionId: String? = null,
    onNewConversation: () -> Unit = {},
    onSwitchSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onDeleteSession: (String) -> Unit = {},
    /** 从设置等功能页退出时置 true：回到聊天页的同时展开侧边栏 */
    startWithDrawerOpen: Boolean = false,
    /** 动作类工具（如"播放《X》"）正等用户点头：非空则弹确认框 */
    pendingConfirm: PendingActConfirm? = null,
    onAnswerConfirm: (Boolean) -> Unit = {},
    /** 侧边栏「悬浮窗聊天」：开关悬浮气泡（不是路由，所以单独回调） */
    onToggleOverlay: () -> Unit = {},
    overlayRunning: Boolean = false,
    onDrawerOpened: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.nekonyan.assistant.data.db.ConversationSession?>(null) }

    // 需求：底部「导入文件」「照片」必须真的能导入（此前是空回调，点了没反应）
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onAttachment(it, true) } }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onAttachment(it, false) } }

    // 侧边栏展开时，系统返回键先关侧边栏（否则会直接把 App 退到桌面）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 需求：从设置等页面退出应回到侧边栏（而不是回到一个"空白聊天页"让人再点一次三条杠）
    LaunchedEffect(startWithDrawerOpen) {
        if (startWithDrawerOpen) {
            drawerState.open()
            onDrawerOpened()
        }
    }

    // 新消息 / 新字进来都要能看到：自动滚到底部
    LaunchedEffect(messages.size, streamingText) {
        val total = messages.size + if (streamingText.isNotEmpty()) 1 else 0
        if (total > 0) listState.scrollToItem(total - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NekoDrawerContent(
                    onSelect = { route ->
                        scope.launch { drawerState.close() }
                        onOpenRoute(route)
                    },
                    onToggleOverlay = onToggleOverlay,
                    overlayRunning = overlayRunning
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },   // 需求：顶部无聊天名
                    navigationIcon = { },  // 需求：顶部无返回键
                    actions = {
                        // 需求：右上角 ＋ 新建对话；长按/点开可管理（切换、改名、删除）
                        IconButton(onClick = onNewConversation) {
                            Icon(Icons.Filled.Add, contentDescription = "新建对话")
                        }
                        IconButton(onClick = { showSessions = true }) {
                            Icon(Icons.Filled.List, contentDescription = "对话管理")
                        }
                        // 需求：右上三条杠展开侧边菜单
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "侧边菜单")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                // 需求：系统按键不许压在输入栏上。
                // 按 safeDrawing 的**底边**留白：导航键隐藏时为 0，键盘弹出时等于键盘高度，
                // 于是手势导航、三键导航、键盘弹起三种情况下输入栏都不会被盖住。
                Column(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    )
                ) {
                    // 需求：始终有紧急停止按钮 —— 挪到键盘上方，任何时候都点得到
                    EmergencyStopButton(
                        onStop = onEmergencyStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )

                    ChatInputBar(
                        value = input,
                        onValueChange = { input = it },
                        onImportFile = { filePicker.launch(arrayOf("*/*")) },
                        onPhoto = {
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onSend = {
                            if (input.isNotBlank()) {
                                onSend(input.trim())
                                input = ""
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 需求：长任务状态常驻可见
                if (runningTaskLabel != null) {
                    RunningTaskBanner(runningTaskLabel)
                }

                // 出错要看得见、能关掉（例如"还没填 API Key"）
                if (errorText != null) {
                    ErrorBanner(errorText, onDismissError)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubble(
                            msg,
                            status = if (msg.fromUser) "已发送" else null,
                            // 直接传上面的稳定引用：原来写 ({ onSpeak(msg.text) })
                            // 会让每个可见气泡在每次重组时都拿到新 lambda → 无法跳过重组
                            onSpeak = if (msg.fromUser) null else onSpeak
                        )
                    }

                    if (streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            ChatBubble(
                                ChatMessage(
                                    id = "streaming",
                                    text = streamingText + " ▍",
                                    fromUser = false
                                ),
                                status = "生成中…"
                            )
                        }
                    } else if (runningTaskLabel != null) {
                        item(key = "thinking") {
                            ChatBubble(ChatMessage(id = "thinking", text = "正在思考…", fromUser = false))
                        }
                    }
                }

            }
        }
    }
    // 动作类工具（会改动外界，如播放音乐）必须用户点头：猫娘先说清要干什么，再等允许。
    // 界面被划走/等待超时都按拒绝处理 —— 没被看见的确认框绝不能变成"默认同意"。
    pendingConfirm?.let { pc ->
        AlertDialog(
            onDismissRequest = { onAnswerConfirm(false) },
            title = { Text("需要你确认") },
            text = { Text("猫娘想${pc.summary}，允许吗？") },
            confirmButton = {
                TextButton(onClick = { onAnswerConfirm(true) }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { onAnswerConfirm(false) }) { Text("拒绝") }
            }
        )
    }

    if (showSessions) {
        SessionManagerDialog(
            sessions = sessions,
            currentId = currentSessionId,
            onSwitch = onSwitchSession,
            onRename = { renaming = it },
            onDelete = onDeleteSession,
            onDismiss = { showSessions = false }
        )
    }

    renaming?.let { target ->
        var text by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("对话名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(target.id, text)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "关闭提示")
            }
        }
    }
}

/** 需求：底部为「输入框、发送、导入文件、照片」四件套 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onImportFile: () -> Unit = {},
    onPhoto: () -> Unit = {}
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onImportFile) {
                Icon(Icons.Filled.AttachFile, contentDescription = "导入文件")
            }
            IconButton(onClick = onPhoto) {
                Icon(Icons.Filled.Image, contentDescription = "照片")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                maxLines = 4,
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.width(8.dp))
            ExtendedFloatingActionButton(
                onClick = onSend,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Send, contentDescription = null) },
                text = { Text("发送") }
            )
        }
    }
}

@Composable
private fun RunningTaskBanner(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "正在执行：$label",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 聊天消息（QQ 风格气泡：自己靠右，对方靠左） */
data class ChatMessage(
    val id: String,
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 气泡：自己靠右、AI 靠左，底部一行显示**时间 + 状态**（`修改.ds` 第二项）。
 * 长文本自动换行：Text 默认 softWrap，气泡宽度限制在 82% 以内，不会横向溢出。
 */
@Composable
private fun ChatBubble(
    msg: ChatMessage,
    status: String? = null,
    onSpeak: ((String) -> Unit)? = null
) {
    val alignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (msg.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (msg.fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 需求：模式色边框 + 轻发光。用**带色阴影**而不是模糊，GPU 代价小得多。
    val modeColor = com.nekonyan.assistant.ui.theme.LocalNekoExtraColors.current.modeBorder
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (msg.fromUser) 18.dp else 4.dp,
        bottomEnd = if (msg.fromUser) 4.dp else 18.dp
    )
    val glow = if (msg.fromUser) {
        Modifier
    } else {
        Modifier.shadow(6.dp, shape, ambientColor = modeColor, spotColor = modeColor)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bg,
            shape = shape,
            border = if (msg.fromUser) null else androidx.compose.foundation.BorderStroke(1.dp, modeColor.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(0.82f).then(glow)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = msg.text,
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge
                )
                // 需求：助手说的话可以朗读（TTS + 可选 RVC 变声）
                if (onSpeak != null) {
                    IconButton(onClick = { onSpeak(msg.text) }, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "朗读这条消息",
                            tint = fg.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatChatTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.7f)
                    )
                    if (status != null) {
                        Text(
                            " · $status",
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/** 聊天时间：只显示时分，聊天列表里精确到秒反而更吵 */
private val chatTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)

private fun formatChatTime(ts: Long): String = chatTimeFormat.format(java.util.Date(ts))

/** 侧边菜单选中项 */
enum class NekoRoute(val label: String) {
    CHAT("聊天"),
    KNOWLEDGE("知识库"),
    TASKS("任务"),
    PERSONA("人格"),
    MUSIC("音乐"),
    YOLO("YOLO 模型"),
    ANNOTATE("标注"),
    PLUGIN("插件"),
    CONFIG("配置"),
    SETTINGS("设置"),
    LOGS("全部日志")
}

/** 对话管理面板：切换 / 改名 / 删除（需求：可管理、可改名称） */
@Composable
private fun SessionManagerDialog(
    sessions: List<com.nekonyan.assistant.data.db.ConversationSession>,
    currentId: String?,
    onSwitch: (String) -> Unit,
    onRename: (com.nekonyan.assistant.data.db.ConversationSession) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对话管理（${sessions.size}）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (sessions.isEmpty()) Text("还没有对话", style = MaterialTheme.typography.bodySmall)
                sessions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (s.id == currentId) "● " else "") + s.title.ifBlank { "新会话" },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                        TextButton(onClick = { onSwitch(s.id); onDismiss() }) { Text("切换") }
                        TextButton(onClick = { onRename(s) }) { Text("改名") }
                        TextButton(onClick = { onDelete(s.id) }) { Text("删除") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
