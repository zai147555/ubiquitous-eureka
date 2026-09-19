package com.nekonyan.assistant.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.ui.theme.EmergencyTextStyle
import com.nekonyan.assistant.ui.theme.NekoTheme

/**
 * 紧急停止（需求原文）：
 *   · **始终有紧急停止按钮**，音量键、通知栏也可停止；
 *   · **紧急停止：严肃红色，不萌化**；
 *   · 插件**不能破坏紧急停止**（因此本组件不接受插件的样式覆盖）。
 *
 * 无障碍：显式提供 contentDescription 且带图标与文字（不只靠颜色，满足色盲友好要求）。
 */
@Composable
fun EmergencyStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val extra = NekoTheme.extra
    Button(
        onClick = onStop,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "紧急停止，立即终止所有自动操作" },
        colors = ButtonDefaults.buttonColors(
            containerColor = extra.emergency,
            contentColor = extra.emergencyContent,
            // 不用主题的萌化圆角：紧急按钮保持方形硬边
            disabledContainerColor = extra.emergency.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("紧急停止", style = EmergencyTextStyle)
        }
    }
}

/** 需求：紧急停止的辅助提示（音量键 / 通知栏） */
@Composable
fun EmergencyStopHint(modifier: Modifier = Modifier) {
    Text(
        text = "音量键 / 通知栏也可停止",
        style = MaterialTheme.typography.labelSmall,
        color = NekoTheme.extra.muted,
        modifier = modifier.padding(horizontal = 4.dp)
    )
}
