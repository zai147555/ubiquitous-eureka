package com.nekonyan.assistant.ui.screen

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.plugin.PluginPolicy
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.db.PluginRecordEntity
import com.nekonyan.assistant.data.repo.PluginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 插件的六个视图（需求：列表/已安装/已启用/已禁用/可更新/日志） */
enum class PluginView(val label: String) {
    LIST("列表"), INSTALLED("已安装"), ENABLED("已启用"),
    DISABLED("已禁用"), UPDATABLE("可更新"), LOGS("日志")
}

data class PluginUiState(
    val plugins: List<PluginRecordEntity> = emptyList(),
    val view: PluginView = PluginView.LIST,
    val expandedId: String? = null,
    val message: String? = null,
    val logLines: List<String> = emptyList()
) {
    val visible: List<PluginRecordEntity>
        get() = when (view) {
            PluginView.ENABLED -> plugins.filter { it.enabled }
            PluginView.DISABLED -> plugins.filter { !it.enabled }
            PluginView.UPDATABLE -> emptyList()   // 未配置更新源时如实为空
            else -> plugins
        }
}

class PluginViewModel(private val repo: PluginRepository) : ViewModel() {

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { list -> _state.update { it.copy(plugins = list) } }
        }
    }

    fun selectView(view: PluginView) = _state.update { it.copy(view = view) }

    fun toggleExpand(id: String) = _state.update { it.copy(expandedId = if (it.expandedId == id) null else id) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun importPlugin(uri: Uri) = viewModelScope.launch {
        val error = repo.importFrom(uri)
        _state.update {
            it.copy(
                message = error ?: "插件已安装（权限默认全部关闭，需单独授权后才生效）",
                logLines = it.logLines + (error?.let { e -> "❌ 导入被拒绝：$e" } ?: "✅ 安装插件包")
            )
        }
    }

    fun setEnabled(record: PluginRecordEntity, enabled: Boolean) = viewModelScope.launch {
        repo.setEnabled(record, enabled)
        _state.update { it.copy(logLines = it.logLines + "${if (enabled) "▶ 启用" else "⏸ 停用"} ${record.name}") }
    }

    fun setPermission(record: PluginRecordEntity, permission: String, granted: Boolean) = viewModelScope.launch {
        repo.setPermission(record, permission, granted)
        _state.update { it.copy(logLines = it.logLines + "${if (granted) "授权" else "收回"} ${record.name} · $permission") }
    }

    fun delete(record: PluginRecordEntity) = viewModelScope.launch {
        repo.delete(record)
        _state.update { it.copy(message = "已卸载「${record.name}」", logLines = it.logLines + "🗑 卸载 ${record.name}") }
    }

    companion object {
        /** 供界面显示"宿主开放了哪些权限" */
        val allowedPermissions: Set<String> get() = PluginPolicy.ALLOWED_PERMISSIONS

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                PluginViewModel(PluginRepository(db.pluginRecordDao(), NekoApp.context()))
            }
        }
    }
}
