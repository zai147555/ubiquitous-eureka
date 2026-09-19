package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.net.ChatOutcome
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.repo.ChatConfigStore
import com.nekonyan.assistant.data.repo.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigUiState(
    val config: ChatConfig = ChatConfig(),
    val dirty: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
    val messageOk: Boolean = false
)

/** 配置页状态：编辑草稿 → 保存（Key 进 Keystore）→ 可选「测试连接」当场验证 */
class ConfigViewModel(private val repo: ChatRepository) : ViewModel() {

    private val _state = MutableStateFlow(ConfigUiState(config = repo.config()))
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    fun edit(transform: (ChatConfig) -> ChatConfig) = _state.update {
        it.copy(config = transform(it.config), dirty = true, message = null)
    }

    fun save() {
        val c = _state.value.config.normalized()
        repo.saveConfig(c)
        val problem = c.blockingProblem()
        _state.update {
            it.copy(
                config = c,
                dirty = false,
                message = problem ?: "已保存（API Key 存于 Android Keystore 加密存储）",
                messageOk = problem == null
            )
        }
    }

    /** 真发一次最小请求：只有真连通才算"配好了"，光看格式说明不了问题 */
    fun testConnection() = viewModelScope.launch {
        val c = _state.value.config.normalized()
        repo.saveConfig(c)
        _state.update { it.copy(config = c, dirty = false, testing = true, message = "正在测试…", messageOk = false) }

        val outcome = withContext(Dispatchers.IO) { repo.ping(c) }
        _state.update {
            when (outcome) {
                is ChatOutcome.Success -> it.copy(testing = false, message = "✅ ${outcome.text}", messageOk = true)
                is ChatOutcome.Failure -> it.copy(testing = false, message = "❌ ${outcome.message}", messageOk = false)
                ChatOutcome.Cancelled -> it.copy(testing = false, message = "已取消", messageOk = false)
            }
        }
    }

    fun clearApiKey() {
        repo.clearApiKey()
        _state.update {
            it.copy(
                config = it.config.copy(apiKey = ""),
                dirty = false,
                message = "已清除本机保存的 API Key",
                messageOk = false
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                ConfigViewModel(
                    ChatRepository(
                        dao = db.conversationDao(),
                        configStore = ChatConfigStore(NekoApp.context())
                    )
                )
            }
        }
    }
}
