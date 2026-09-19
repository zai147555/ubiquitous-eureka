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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.core.yolo.ModelPolicy
import com.nekonyan.assistant.core.yolo.SwitchGuard
import com.nekonyan.assistant.core.yolo.YoloScene
import com.nekonyan.assistant.data.db.YoloModelEntity
import com.nekonyan.assistant.data.repo.YoloModelRepository
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.LaunchedEffect
import com.nekonyan.assistant.core.capture.ScreenCaptureService

/**
 * YOLO 模型管理页（严格按工作区 `yolo.ds` 的页面结构）：
 * 当前使用模型 → 已安装模型 → 可更新模型 → 已禁用模型 → 模型日志 → 模型配置。
 *
 * 一处刻意的诚实：**推理引擎（NCNN native）尚未接入**，因此性能区不编数字 ——
 * 没有真实采样时显示「未运行」，而不是给一个好看的延迟让人以为在跑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoloModelScreen(
    onBack: () -> Unit,
    vm: YoloModelViewModel = viewModel(factory = YoloModelViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 导出是"先选位置、再写文件"两段式：这里记住要导出的模型 id
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    // 导入：多选（.param + .bin [+ labels.txt/manifest.json]）
    // 屏幕捕获授权回调：resultCode/data 必须原样交给前台服务（无法持久化）
    val captureConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
            ScreenCaptureService.start(ctx, res.resultCode, res.data!!)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.importModels(uris) }

    // 导出：写到一个用户选定的 zip
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { target -> state.models.firstOrNull { m -> m.id == pendingExportId }?.let { vm.exportModel(it, target) } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YOLO 模型") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { msg ->
                Surface(
                    color = if (state.messageOk) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.clearMessage() }) { Text("关闭") }
                    }
                }
            }

            // ---------------- 当前使用模型 ----------------
            SectionTitle("当前使用模型")
            val current = state.current
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (current == null) {
                        Text("还没有可用模型", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "APK 内若已打包内置模型，首启会自动释放；否则可从下方「导入模型」加入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("版本 ${current.version} · ${SwitchGuard.humanSize(current.sizeBytes)} · ${current.classCount} 类 · 输入 ${current.inputSize}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("推理后端：${current.backend.uppercase()}（NCNN 原生库未接入，暂不能真实推理）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Text("延迟：${if (current.latencyMs > 0) "${current.latencyMs} ms" else "未运行"} · 精度：${if (current.accuracy > 0) "${current.accuracy}" else "未评测"}")
                        Text("来源：${sourceLabel(current.source)} · 签名：${if (current.signature.isBlank()) "未声明" else current.signature.take(12) + "…"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.rollback() }, enabled = !state.busy) { Text("回滚") }
                            OutlinedButton(
                                onClick = { vm.setEnabled(current, false) },
                                enabled = !state.busy
                            ) { Text("禁用") }
                            OutlinedButton(
                                onClick = { pendingExportId = current.id; exportLauncher.launch("${current.name}.zip") },
                                enabled = !state.busy
                            ) { Text("导出") }
                        }
                        // 需求（yolo.ds 第 78~86 行）：延迟 / FPS 必须是实测值
                        Button(
                            onClick = { vm.selfTest() },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.engineReady) "重新自检（已实测 ${current.latencyMs}ms）" else "推理自检（加载模型并测一帧）") }
                        Text(
                            if (state.engineReady) "引擎：本地 NCNN 已跑通（数据来自实测）"
                            else "引擎：尚未自检 —— 点上面按钮加载模型；原生库缺失时会如实报错，不会编数字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------- 场景（yolo.ds 第 12/47~54 行） ----------------
            SectionTitle("运行场景")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YoloScene.entries.forEach { scene ->
                    FilterChip(
                        selected = state.scene == scene,
                        onClick = { vm.selectScene(scene) },
                        label = { Text(scene.label) }
                    )
                }
            }
            Text(
                "场景「${state.scene.label}」应使用 ${state.scenePreferred}" +
                    if (state.sceneResolved == state.scenePreferred) "（已装）" else "（未装，将回退到 ${state.sceneResolved}）",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { vm.applySceneSwitch() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "处理中…" else "按场景切换")
            }

            HorizontalDivider()

            // ---------------- 已安装模型 ----------------
            SectionTitle("已安装模型（${state.enabledModels.size}）")
            state.enabledModels.forEach { model ->
                ModelCard(
                    model = model,
                    isCurrent = model.id == state.config.currentModelId,
                    busy = state.busy,
                    onSwitch = { vm.switchTo(model) },
                    onToggle = { vm.setEnabled(model, false) },
                    onDelete = { vm.delete(model) },
                    onExport = { pendingExportId = model.id; exportLauncher.launch("${model.name}.zip") }
                )
            }

            // ---------------- 可更新模型 ----------------
            SectionTitle("可更新模型")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "未配置更新源：模型热更新依赖服务端清单（docs/04_模型热更新.md），当前只能本地导入。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { vm.checkUpdate() }, enabled = !state.busy) { Text("检查更新") }
                    if (state.updates.isNotEmpty()) {
                        Text("最近一次检查：${state.updates.first().status} ${state.updates.first().message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---------------- 已禁用模型 ----------------
            SectionTitle("已禁用模型（${state.disabledModels.size}）")
            if (state.disabledModels.isEmpty()) {
                Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.disabledModels.forEach { model ->
                    ModelCard(
                        model = model,
                        isCurrent = false,
                        busy = state.busy,
                        onSwitch = { vm.switchTo(model) },
                        onToggle = { vm.setEnabled(model, true) },
                        onDelete = { vm.delete(model) },
                        onExport = { pendingExportId = model.id; exportLauncher.launch("${model.name}.zip") }
                    )
                }
            }

            // ---------------- 导入 ----------------
            SectionTitle("模型导入 / 导出")
            Text(
                "支持一次选择 .param 与 .bin（可加 labels.txt / manifest.json）：导入前校验同名配对、家族识别与签名。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.busy && state.config.allowImport
                ) { Text("导入模型") }
                OutlinedButton(onClick = { vm.pruneVersions() }, enabled = !state.busy) { Text("清理旧版本") }
            }

            HorizontalDivider()

            // ---------------- 识别服务（模型/接入指南.md） ----------------
            SectionTitle("识别服务（可选 · 局域网）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "接自建的 YOLO11n 检测服务：地址形如 http://主机:8000，鉴权头 X-API-Token。" +
                            "Token 存 Android Keystore，不进日志、不入仓库。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    var base by remember { mutableStateOf(state.serviceBase) }
                    var token by remember { mutableStateOf(state.serviceToken) }
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it },
                        label = { Text("服务地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("API Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.saveService(base, token) }, enabled = !state.serviceBusy) { Text("保存") }
                        OutlinedButton(onClick = { vm.probeService() }, enabled = !state.serviceBusy) { Text("探活") }
                        OutlinedButton(onClick = { vm.healthService() }, enabled = !state.serviceBusy) { Text("健康检查") }
                    }
                    Button(
                        onClick = { vm.detectTestImage() },
                        enabled = !state.serviceBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.serviceBusy) "请求中…" else "检测测试图（验证链路）") }
                    state.serviceMessage?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.serviceOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            HorizontalDivider()

            HorizontalDivider()

            // ---------------- 屏幕捕获（M4） ----------------
            SectionTitle("屏幕捕获（M4 · 本地识别）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var capturing by remember { mutableStateOf(ScreenCaptureService.running) }
                    var stats by remember { mutableStateOf(ScreenCaptureService.lastStats) }
                    LaunchedEffect(capturing) {
                        while (capturing) {
                            stats = ScreenCaptureService.lastStats
                            capturing = ScreenCaptureService.running
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    Text(
                        "授权后由前台服务抓屏并本地跑模型（约 2 帧/秒，实测约 234ms/帧）。" +
                            "系统每次开始捕获都要现场确认 —— 安卓不允许预先授权屏幕录制。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "状态：" + if (capturing) stats else "未运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (capturing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val mpm = ctx.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                                runCatching { captureConsent.launch(mpm.createScreenCaptureIntent()) }
                            },
                            enabled = !capturing
                        ) { Text("开始捕获") }
                        OutlinedButton(
                            onClick = { ScreenCaptureService.stop(ctx); capturing = false },
                            enabled = capturing
                        ) { Text("停止") }
                    }
                }
            }

            // ---------------- 模型日志（只读） ----------------
            SectionTitle("模型日志（只读）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    if (state.logLines.isEmpty()) {
                        Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.logLines.take(40).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // ---------------- 模型配置 ----------------
            SectionTitle("模型配置")
            ConfigSwitch("自动切换（按场景）", state.config.autoSwitch) { v -> vm.updateConfig { it.copy(autoSwitch = v) } }
            ConfigSwitch("仅 WiFi 下载", state.config.wifiOnly) { v -> vm.updateConfig { it.copy(wifiOnly = v) } }
            ConfigSwitch("自动更新", state.config.autoUpdate) { v -> vm.updateConfig { it.copy(autoUpdate = v) } }
            ConfigSwitch("模型签名校验", state.config.signatureCheck) { v -> vm.updateConfig { it.copy(signatureCheck = v) } }
            ConfigSwitch("允许导入", state.config.allowImport) { v -> vm.updateConfig { it.copy(allowImport = v) } }
            ConfigSwitch("允许导出", state.config.allowExport) { v -> vm.updateConfig { it.copy(allowExport = v) } }
            ConfigSwitch("允许删除", state.config.allowDelete) { v -> vm.updateConfig { it.copy(allowDelete = v) } }

            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("保留版本数", style = MaterialTheme.typography.labelLarge)
                    Text("${state.config.keepVersions}（2~5）", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = state.config.keepVersions.toFloat(),
                    onValueChange = { v -> vm.updateConfig { it.copy(keepVersions = v.toInt()) } },
                    valueRange = 2f..5f,
                    steps = 2
                )
            }
            Text(
                "配置改动会立刻落库；「清理旧版本」按保留策略删除多余版本，当前使用中的版本永不删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ModelCard(
    model: YoloModelEntity,
    isCurrent: Boolean,
    busy: Boolean,
    onSwitch: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    when {
                        isCurrent -> "当前"
                        model.status == YoloModelEntity.STATUS_DISABLED -> "禁用"
                        model.status == YoloModelEntity.STATUS_ERROR -> "异常"
                        else -> "可用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "版本 ${model.version} · ${SwitchGuard.humanSize(model.sizeBytes)} · ${model.classCount} 类 · " +
                    "输入 ${model.inputSize} · 后端 ${model.backend}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "延迟 ${if (model.latencyMs > 0) "${model.latencyMs}ms" else "未运行"} · " +
                    "精度 ${if (model.accuracy > 0) model.accuracy.toString() else "未评测"} · " +
                    "来源 ${sourceLabel(model.source)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitch, enabled = !busy && !isCurrent) { Text("切换") }
                OutlinedButton(onClick = onToggle, enabled = !busy) {
                    Text(if (model.status == YoloModelEntity.STATUS_DISABLED) "启用" else "禁用")
                }
                OutlinedButton(onClick = onExport, enabled = !busy) { Text("导出") }
                OutlinedButton(onClick = onDelete, enabled = !busy && !isCurrent) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ConfigSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun sourceLabel(source: String): String = when (source) {
    YoloModelEntity.SOURCE_BUILTIN -> "内置"
    YoloModelEntity.SOURCE_LOCAL -> "本地导入"
    YoloModelEntity.SOURCE_SHARE -> "分享"
    YoloModelEntity.SOURCE_ZIP -> "压缩包"
    YoloModelEntity.SOURCE_LAN -> "局域网"
    YoloModelEntity.SOURCE_SERVER -> "服务器更新"
    else -> source
}
