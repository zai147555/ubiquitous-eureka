package com.nekonyan.assistant.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.ui.screen.NekoRoute

/**
 * 侧边菜单（需求：右上三条杠展开）。顺序为：
 * 聊天、知识库、任务、**人格**、音乐、**YOLO 模型**、插件、配置、设置、全部日志。
 * 「聊天」这一条是保留的既有入口 —— 需求清单里没列它，但侧边栏是唯一导航入口，
 * 去掉它用户从功能页返回后就没有回到聊天的入口了（`修改.ds`：不破坏现有入口）。
 *
 * 菜单顺序与文案严格按需求给出，不额外增删 —— 插件的自定义 UI 能力（需求：插件可改所有 UI）
 * 走 plugin 模块，不通过在这里加条目实现。
 */
data class DrawerEntry(val route: NekoRoute, val icon: ImageVector)

val NekoDrawerEntries: List<DrawerEntry> = listOf(
    DrawerEntry(NekoRoute.CHAT, Icons.AutoMirrored.Outlined.Article),
    DrawerEntry(NekoRoute.KNOWLEDGE, Icons.Filled.MenuBook),
    DrawerEntry(NekoRoute.TASKS, Icons.Filled.Build),
    DrawerEntry(NekoRoute.PERSONA, Icons.Filled.Person),
    DrawerEntry(NekoRoute.MUSIC, Icons.Filled.MusicNote),
    DrawerEntry(NekoRoute.YOLO, Icons.Filled.Memory),
    DrawerEntry(NekoRoute.PLUGIN, Icons.Filled.Extension),
    DrawerEntry(NekoRoute.CONFIG, Icons.Filled.Tune),
    DrawerEntry(NekoRoute.SETTINGS, Icons.Filled.Settings),
    DrawerEntry(NekoRoute.LOGS, Icons.AutoMirrored.Outlined.Article)
)

@Composable
fun NekoDrawerContent(
    onSelect: (NekoRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "猫娘助手",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "本地 YOLO · 云端 DeepSeek",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        NekoDrawerEntries.forEach { entry ->
            NavigationDrawerItem(
                label = { Text(entry.route.label) },
                icon = { Icon(entry.icon, contentDescription = null) },
                selected = entry.route == NekoRoute.CHAT,
                onClick = { onSelect(entry.route) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}
