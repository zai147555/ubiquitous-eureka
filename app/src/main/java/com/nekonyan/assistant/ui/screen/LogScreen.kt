package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.data.db.LogEntry
import com.nekonyan.assistant.ui.theme.NekoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全部日志（需求：侧边菜单含「全部日志」；**日志只读，不可删除**；
 * 可筛选级别与模块；字段为 时间/级别/模块/事件/详情/设备ID/会话ID）
 *
 * 界面刻意**不提供任何删除或清空按钮** —— 只读是需求硬约束，
 * 不给用户"以为能删"的入口。容量管理由 NekoLog 的保留期轮转负责。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    vm: LogViewModel = viewModel(factory = LogViewModel.Factory)
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全部日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 需求：日志只读 —— 用锁图标明确告知，而不是放一个灰色的删除按钮
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "日志为只读，不可删除",
                        tint = NekoTheme.extra.muted,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ReadOnlyNotice()
            FilterRow(
                modules = LogViewModel.MODULES,
                levels = LogViewModel.LEVELS,
                selectedModule = filter.module,
                selectedLevel = filter.level,
                onModule = vm::setModule,
                onLevel = vm::setLevel,
                onClear = vm::clearFilter
            )
            HorizontalDivider()
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志", color = NekoTheme.extra.muted)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(entries, key = { it.id }) { e -> LogRow(e) }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = NekoTheme.extra.muted,
            modifier = Modifier.height(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "日志为只读，不可删除（敏感字段已脱敏）",
            style = MaterialTheme.typography.labelSmall,
            color = NekoTheme.extra.muted
        )
    }
}

@Composable
private fun FilterRow(
    modules: List<String>,
    levels: List<String>,
    selectedModule: String?,
    selectedLevel: String?,
    onModule: (String?) -> Unit,
    onLevel: (String?) -> Unit,
    onClear: () -> Unit
) {
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                AssistChip(onClick = onClear, label = { Text("全部") })
            }
            items(levels) { lv ->
                FilterChip(
                    selected = selectedLevel == lv,
                    onClick = { onLevel(if (selectedLevel == lv) null else lv) },
                    label = { Text(lv) }
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(modules) { m ->
                FilterChip(
                    selected = selectedModule == m,
                    onClick = { onModule(if (selectedModule == m) null else m) },
                    label = { Text(m) }
                )
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

@Composable
private fun LogRow(e: LogEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(e.level)
            Spacer(Modifier.width(8.dp))
            Text(
                e.module,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                timeFmt.format(Date(e.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = NekoTheme.extra.muted
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(e.event, style = MaterialTheme.typography.bodyMedium)
        if (e.detail.isNotBlank()) {
            Text(
                e.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LevelBadge(level: String) {
    val color = when (level) {
        LogEntry.LEVEL_DEBUG -> Color(0xFF78909C)
        LogEntry.LEVEL_INFO -> Color(0xFF2E7D32)
        LogEntry.LEVEL_WARN -> Color(0xFFEF6C00)
        LogEntry.LEVEL_ERROR -> Color(0xFFC62828)
        LogEntry.LEVEL_FATAL -> Color(0xFF6A1B9A)
        else -> NekoTheme.extra.muted
    }
    Box(
        Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            level,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}
