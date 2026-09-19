package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.nekonyan.assistant.data.db.AIPersona

/**
 * 人格管理页（`修改.ds` 第一项）。
 *
 * 只保留需求指定的两个字段：**人格名称 + 人格描述**。
 * 支持 新增 / 编辑 / 删除 / 复制 / 设为默认 / 切换当前；
 * 当前人格用于聊天、悬浮窗与任务执行（悬浮窗随 M3 落地，聊天与任务已接）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    onBack: () -> Unit,
    vm: PersonaViewModel = viewModel(factory = PersonaViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AIPersona?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人格") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新增人格") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.clearMessage() }) { Text("关闭") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前人格", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(state.currentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "用于聊天、悬浮窗与任务执行；切换会写入只读日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()
            Text("全部人格（${state.personas.size}）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            state.personas.forEach { persona ->
                PersonaCard(
                    persona = persona,
                    isCurrent = persona.id == state.currentId,
                    onSwitch = { vm.switchCurrent(persona) },
                    onSetDefault = { vm.setDefault(persona) },
                    onEdit = { editing = persona },
                    onDuplicate = { vm.duplicate(persona) },
                    onDelete = { vm.delete(persona) }
                )
            }
        }
    }

    if (creating) {
        PersonaDialog(
            title = "新增人格",
            initialName = "",
            initialDesc = "",
            onDismiss = { creating = false },
            onConfirm = { name, desc -> vm.create(name, desc); creating = false }
        )
    }

    editing?.let { persona ->
        PersonaDialog(
            title = "编辑人格",
            initialName = persona.name,
            initialDesc = persona.description,
            onDismiss = { editing = null },
            onConfirm = { name, desc -> vm.update(persona, name, desc); editing = null }
        )
    }
}

@Composable
private fun PersonaCard(
    persona: AIPersona,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    persona.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (persona.isDefault) Badge("默认")
                if (isCurrent) Badge("当前")
            }
            if (persona.description.isNotBlank()) {
                Text(persona.description, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitch, enabled = !isCurrent) { Text("切换") }
                OutlinedButton(onClick = onSetDefault, enabled = !persona.isDefault) { Text("设为默认") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDuplicate) { Text("复制") }
                OutlinedButton(onClick = onDelete, enabled = !persona.isDefault) { Text("删除") }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** 人格编辑对话框：按需求**只有名称与描述**两个输入 */
@Composable
private fun PersonaDialog(
    title: String,
    initialName: String,
    initialDesc: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("人格名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("人格描述") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, desc) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
