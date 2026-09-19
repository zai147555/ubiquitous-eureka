package com.nekonyan.assistant.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.data.db.PluginRecordEntity

/**
 * 插件页（M15 · 方案 A：**声明式插件**）。
 *
 * 六个视图齐备：列表 / 已安装 / 已启用 / 已禁用 / 可更新 / 日志。
 * 插件包就是一个 `plugin.json` —— **不含任何可执行代码**，因此"插件可改所有 UI"
 * 与"不能破坏紧急停止、权限入口、日志只读与安全提示"能同时成立：
 * 后四者是宿主白名单，插件清单里出现相关键名会被**整包拒绝**（见 core/plugin/PluginPolicy）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    onBack: () -> Unit,
    vm: PluginViewModel = viewModel(factory = PluginViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importPlugin(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Filled.Extension, contentDescription = "导入插件（plugin.json）")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 安全边界必须写在用户看得见的地方
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("插件的边界", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "插件可改主题色、加侧边栏项、加提示词模板与配置项；" +
                            "**不能**改紧急停止按钮、权限入口、日志只读与安全提示 —— " +
                            "清单里出现这些键名会被整包拒绝。权限需单独授权后才生效。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            state.message?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.clearMessage() }) { Text("关闭") }
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PluginView.entries.forEach { v ->
                    FilterChip(
                        selected = state.view == v,
                        onClick = { vm.selectView(v) },
                        label = { Text(v.label) }
                    )
                }
            }

            HorizontalDivider()

            when (state.view) {
                PluginView.UPDATABLE -> Text(
                    "未配置插件更新源：插件目前只能从本地 plugin.json 导入（服务端分发尚未部署）。",
                    style = MaterialTheme.typography.bodySmall
                )

                PluginView.LOGS -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.logLines.isEmpty()) Text("本次会话暂无插件操作", style = MaterialTheme.typography.bodySmall)
                        state.logLines.takeLast(30).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            "插件操作同时写入**只读日志表**（模块 PLUGIN），可在侧边栏「全部日志」查看。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    if (state.visible.isEmpty()) {
                        Text(
                            if (state.plugins.isEmpty())
                                "还没有插件：点右上角图标选一个 plugin.json 导入。"
                            else "该视图下没有插件。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.visible.forEach { record ->
                        PluginCard(
                            record = record,
                            expanded = state.expandedId == record.id,
                            onToggleExpand = { vm.toggleExpand(record.id) },
                            onEnable = { vm.setEnabled(record, !record.enabled) },
                            onDelete = { vm.delete(record) },
                            onPermission = { p, granted -> vm.setPermission(record, p, granted) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    record: PluginRecordEntity,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEnable: () -> Unit,
    onDelete: () -> Unit,
    onPermission: (String, Boolean) -> Unit
) {
    val granted = record.grantedList()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (record.enabled) "已启用" else "已禁用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "v${record.version}" + if (record.author.isNotBlank()) " · ${record.author}" else "" + " · ${record.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.description.isNotBlank()) {
                Text(record.description, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEnable) { Text(if (record.enabled) "禁用" else "启用") }
                OutlinedButton(onClick = onToggleExpand) { Text(if (expanded) "收起权限" else "权限(${granted.size})") }
                OutlinedButton(onClick = onDelete) { Text("卸载") }
            }

            if (expanded) {
                Text("权限单独授权（未授权的能力不生效）", style = MaterialTheme.typography.labelSmall)
                PluginViewModel.allowedPermissions.sorted().forEach { permission ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(permission, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = permission in granted,
                            onCheckedChange = { onPermission(permission, it) }
                        )
                    }
                }
            }
        }
    }
}
