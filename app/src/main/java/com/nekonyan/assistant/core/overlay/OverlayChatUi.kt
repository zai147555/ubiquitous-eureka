package com.nekonyan.assistant.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekonyan.assistant.ui.screen.ChatMessage
import com.nekonyan.assistant.ui.screen.ChatUiState

/**
 * 悬浮窗内容：收起时是个可拖动的圆气泡，展开时是迷你聊天窗。
 *
 * 三个刻意的决定：
 *   ① **确认弹窗内联显示，不用 Dialog** —— 悬浮窗本身就是一层系统窗口，
 *      在它上面再开 Dialog 会再建一层窗口，焦点/权限都容易出问题；内联一行反而更稳；
 *   ② **只显示最近 30 条**：悬浮窗是"随手看一眼"，不是完整聊天页，
 *      无限列表还会让 LazyColumn 的高度撑破屏幕；
 *   ③ **拖动同时绑在气泡和标题栏上**：展开后气泡本身被面板挡住，
 *      没有标题栏拖动就没法挪位置了。
 */
@Composable
fun OverlayBubble(
    streaming: Boolean,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
            .clickable { onClick() }
    ) {
        Text(
            "猫",
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        // 正在回复时右上角一个红点：收起状态下也能看出"它在干活"
        if (streaming) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .background(Color(0xFFFF5252), CircleShape)
            )
        }
    }
}

@Composable
fun OverlayPanel(
    chat: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onAnswerConfirm: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val shown = chat.messages.takeLast(30)

    LaunchedEffect(shown.size, chat.streamingText) {
        val n = shown.size + if (chat.streamingText.isNotBlank()) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.width(300.dp)) {

            // ---- 标题栏（拖动把手）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                onDrag(drag.x, drag.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    }
                    .padding(start = 10.dp, end = 2.dp)
            ) {
                Text(
                    "猫娘助手",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭悬浮窗")
                }
            }

            // ---- 状态条：工具调用中 / 正在回复 / 错误 ----
            (chat.toolStatus ?: if (chat.streaming) "正在回复…" else null)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            chat.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }

            // ---- 消息 ----
            if (shown.isEmpty() && chat.streamingText.isBlank()) {
                Text(
                    "还没有对话。在这里说一句，和 App 里是同一份记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    items(shown, key = { it.id }) { m -> OverlayMessageRow(m) }
                    if (chat.streamingText.isNotBlank()) {
                        item("__streaming__") {
                            OverlayMessageRow(
                                ChatMessage(id = "__streaming__", text = chat.streamingText, fromUser = false)
                            )
                        }
                    }
                }
            }

            // ---- 动作类工具确认（内联）----
            chat.pendingConfirm?.let { pc ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(8.dp)
                ) {
                    Text("猫娘想${pc.summary}，允许吗？", style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onAnswerConfirm(false) }) { Text("拒绝") }
                        TextButton(onClick = { onAnswerConfirm(true) }) { Text("允许") }
                    }
                }
            }

            // ---- 输入 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    modifier = Modifier.weight(1f)
                )
                if (chat.streaming) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止")
                    }
                } else {
                    IconButton(
                        onClick = {
                            val t = input.trim()
                            if (t.isNotEmpty()) {
                                onSend(t)
                                input = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayMessageRow(m: ChatMessage) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                m.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}
