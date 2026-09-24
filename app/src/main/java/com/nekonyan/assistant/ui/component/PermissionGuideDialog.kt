package com.nekonyan.assistant.ui.component

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekonyan.assistant.core.perm.PermissionChecker
import com.nekonyan.assistant.core.perm.PermissionGuide
import com.nekonyan.assistant.core.perm.PermissionState

/**
 * 首次启动的**分步权限引导**（`修改.ds` 第五项）。
 *
 * 需求逐条对应：
 *   · 分步骤展示、每项说明用途 → 一次一项 + 用途文案；
 *   · 提供跳转按钮 → 能申请的走系统弹窗，不能申请的跳系统设置页；
 *   · 实时检测、已授权打勾 → 每步与每次操作后都重新检测（`刷新状态` 按钮也在）；
 *   · 允许跳过 / 稍后从设置重新进入 → 跳过即记「已引导」，设置页可随时再来；
 *   · 未授权要降级并提示、不能崩溃 → 未授权时显示降级说明，且所有检测都容错。
 */
@Composable
fun PermissionGuideDialog(
    onFinish: () -> Unit,
    onSkip: () -> Unit
) {
    val ctx = LocalContext.current
    var index by remember { mutableStateOf(0) }
    var states by remember { mutableStateOf(PermissionChecker.allStates(ctx)) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { states = PermissionChecker.allStates(ctx) }

    // 屏幕录制无法预授权：这里真的把系统确认框调起来，让用户看到它是可用的、只是必须现场同意
    var captureConfirmed by remember { mutableStateOf(false) }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        captureConfirmed = res.resultCode == android.app.Activity.RESULT_OK && res.data != null
        states = PermissionChecker.allStates(ctx)
    }

    val item = PermissionGuide.ITEMS[index]
    val state = states[item.key]
    val progress = PermissionGuide.progress(states)
    val isLast = index == PermissionGuide.ITEMS.lastIndex

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("权限引导 ${index + 1}/${PermissionGuide.ITEMS.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (item.required) "必需" else "可选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(item.purpose, style = MaterialTheme.typography.bodyMedium)

                Text(
                    "状态：" + when (state) {
                        PermissionState.GRANTED -> "✅ 已授权"
                        PermissionState.DENIED -> "⬜ 未授权"
                        PermissionState.MANUAL -> "⚙ 需手动开启（系统不提供查询接口）"
                        PermissionState.NOT_APPLICABLE -> "— 当前系统不需要"
                        null -> "检测中…"
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Text(progress.summary, style = MaterialTheme.typography.labelSmall)

                if (state == PermissionState.MANUAL) {
                    Text(
                        "注意：这是厂商私有开关，安卓没有公开接口能查它的状态 —— " +
                            "即使你在系统设置里开了，这一步也不会变成 ✅。这是系统限制，不是应用没检测到。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state != PermissionState.GRANTED && state != PermissionState.NOT_APPLICABLE) {
                    Text(
                        PermissionGuide.degradationHint(item.key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { states = PermissionChecker.allStates(ctx) }) { Text("刷新") }
                OutlinedButton(onClick = {
                    val runtime = PermissionChecker.runtimePermissions(item.key)
                    if (item.key == PermissionGuide.KEY_SCREEN_CAPTURE) {
                        // 没有可申请的权限、也没有设置页 → 直接弹系统的投屏确认框
                        val mpm = ctx.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                        runCatching { captureLauncher.launch(mpm.createScreenCaptureIntent()) }
                    } else if (runtime.isNotEmpty()) {
                        runtimeLauncher.launch(runtime.toTypedArray())
                    } else {
                        PermissionChecker.jumpIntent(ctx, item.key)?.let { intent ->
                            runCatching { ctx.startActivity(intent as Intent) }
                        }
                    }
                    states = PermissionChecker.allStates(ctx)
                }) { Text(if (state == PermissionState.GRANTED) "重新检查" else "去授权") }

                Button(onClick = {
                    states = PermissionChecker.allStates(ctx)
                    if (isLast) onFinish() else index += 1
                }) { Text(if (isLast) "完成" else "下一步") }
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("跳过") }
        }
    )
}
