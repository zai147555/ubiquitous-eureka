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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.data.db.NekoMode
import com.nekonyan.assistant.data.db.Task
import com.nekonyan.assistant.ui.theme.NekoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nekonyan.assistant.core.util.NekoMode

/**
 * 任务页（需求原文）：
 *   · 任务必须填写「任务名称 + 任务消息」；
 *   · 点击任务自动发送任务消息给 AI，无需手动输入；
 *   · 任务列表显示 名称、消息摘要、模式、状态、最后执行时间；
 *   · 按模式区分，各模式独立列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    onAutoSend: (String) -> Unit = {},
    vm: TasksViewModel = viewModel(factory = TasksViewModel.Factory)
) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val pending by vm.pendingMessage.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    // 需求：点击任务 → 自动发送消息给 AI，并回到聊天界面
    LaunchedEffect(pending) {
        pending?.let {
            onAutoSend(it)
            vm.consumePendingMessage()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务") },
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
        ) {
            // 需求：按模式区分，各模式独立列表
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(NekoMode.entries.toList()) { m ->
                    FilterChip(
                        selected = m == mode,
                        onClick = { vm.setMode(m) },
                        label = { Text(m.label) }
                    )
                }
            }

            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = NekoTheme.extra.emergency,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }

            // ---- 新建任务（名称 + 消息，两者必填）----
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "新建任务（${mode.label}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("任务名称（必填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("任务消息（必填，点击任务时自动发送）") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "支持 {{变量}} 模板，如 {{物资}}、{{配枪}}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NekoTheme.extra.muted,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            vm.create(name, message)
                            name = ""
                            message = ""
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                }
            }

            // ---- 任务列表 ----
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("「${mode.label}」模式暂无任务", color = NekoTheme.extra.muted)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks, key = { it.id }) { t -> TaskCard(t, vm) }
                }
            }
        }
    }
}

private val runTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
private fun TaskCard(task: Task, vm: TasksViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // 需求：任务列表显示消息摘要
                        task.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 需求：点击任务自动执行 —— 一键发送按钮
                IconButton(onClick = { vm.run(task) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "执行任务并发送消息",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { vm.delete(task) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除任务")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeBadge(NekoMode.fromKey(task.mode).label)
                Spacer(Modifier.width(8.dp))
                Text(
                    // 需求：显示状态与最后执行时间
                    buildString {
                        append(task.lastStatus ?: "未执行")
                        task.lastRunAt?.let { append(" · ").append(runTimeFmt.format(Date(it))) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (task.enabled) "启用" else "停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = NekoTheme.extra.muted
                )
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = task.enabled,
                    onCheckedChange = { vm.setEnabled(task, it) }
                )
            }
        }
    }
}

@Composable
private fun ModeBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .padding(0.dp)
            .then(Modifier)
    )
}
