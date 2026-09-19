package com.nekonyan.assistant.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.core.chat.ChatConfig

/**
 * 配置页（需求：API Base URL、Key、文本/视觉模型、超时…）。
 *
 * 三条刻意的取舍：
 *   · **保存时立刻校验**：地址不是 https、Key 空着都会当场说清原因，而不是等聊天时报 401；
 *   · **「测试连接」真发一次最小请求**（只要 8 个 token）：格式对不等于能用，
 *     只有真连通才算配好 —— 这是用户最容易省掉、也最容易踩坑的一步；
 *   · Key 的显示/隐藏开关默认关闭，且任何日志路径都不打印明文
 *     （见 [ChatConfig.describeForLog] 与 core/log/Redactor）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    vm: ConfigViewModel = viewModel(factory = ConfigViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val config = state.config
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置") },
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
            SectionTitle("模型服务")

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { v -> vm.edit { it.copy(baseUrl = v) } },
                label = { Text("API 地址") },
                supportingText = { Text("必须 https；默认 ${ChatConfig.DEFAULT_BASE_URL}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { v -> vm.edit { it.copy(apiKey = v) } },
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                },
                supportingText = { Text("存于 Android Keystore（AES-256-GCM），不进日志、不随备份导出") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("模型", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatConfig.KNOWN_MODELS.forEach { model ->
                    FilterChip(
                        selected = config.model == model,
                        onClick = { vm.edit { it.copy(model = model) } },
                        label = { Text(model) }
                    )
                }
            }
            OutlinedTextField(
                value = config.model,
                onValueChange = { v -> vm.edit { it.copy(model = v) } },
                label = { Text("模型名（也可直接填别的兼容模型）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            SectionTitle("生成参数")

            ParamSlider(
                label = "超时",
                valueText = "${config.timeoutSeconds} 秒",
                value = config.timeoutSeconds.toFloat(),
                range = ChatConfig.MIN_TIMEOUT_SECONDS.toFloat()..ChatConfig.MAX_TIMEOUT_SECONDS.toFloat(),
                onChange = { v -> vm.edit { it.copy(timeoutSeconds = v.toInt()) } }
            )
            ParamSlider(
                label = "温度",
                valueText = String.format("%.1f", config.temperature),
                value = config.temperature.toFloat(),
                range = ChatConfig.MIN_TEMPERATURE.toFloat()..ChatConfig.MAX_TEMPERATURE.toFloat(),
                onChange = { v -> vm.edit { it.copy(temperature = v.toDouble()) } }
            )
            ParamSlider(
                label = "单次最大输出",
                valueText = "${config.maxTokens} tokens",
                value = config.maxTokens.toFloat(),
                range = ChatConfig.MIN_MAX_TOKENS.toFloat()..ChatConfig.MAX_MAX_TOKENS.toFloat(),
                onChange = { v -> vm.edit { it.copy(maxTokens = v.toInt()) } }
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.save() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.dirty) "保存" else "已保存") }

                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = !state.testing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.testing) "测试中…" else "测试连接") }
            }

            TextButton(onClick = { vm.clearApiKey() }) { Text("清除本机保存的 API Key") }

            state.message?.let { msg ->
                Surface(
                    color = if (state.messageOk) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                "当前请求地址：${config.normalized().endpoint()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
