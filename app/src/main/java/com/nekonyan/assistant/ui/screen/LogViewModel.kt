package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.data.db.LogEntry
import com.nekonyan.assistant.data.db.NekoDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 日志页状态。
 *
 * 需求约束（体现在接口形态上）：
 *   · 日志**只读**：本 ViewModel 只有"查询"能力，没有任何删除/清空方法；
 *   · 可筛选：按级别、按模块；
 *   · 导出前脱敏由 NekoLog/Redactor 在写入时已完成，导出的就是脱敏后的内容。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel(db: NekoDatabase) : ViewModel() {

    private val dao = db.logDao()

    data class Filter(val level: String? = null, val module: String? = null)

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter

    val entries: StateFlow<List<LogEntry>> = _filter
        .flatMapLatest { f -> dao.observeFiltered(f.level, f.module, limit = 800) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLevel(level: String?) { _filter.value = _filter.value.copy(level = level) }
    fun setModule(module: String?) { _filter.value = _filter.value.copy(module = module) }
    fun clearFilter() { _filter.value = Filter() }

    companion object {
        /** 需求：日志级别 DEBUG/INFO/WARN/ERROR/FATAL */
        val LEVELS = listOf(
            LogEntry.LEVEL_DEBUG, LogEntry.LEVEL_INFO, LogEntry.LEVEL_WARN,
            LogEntry.LEVEL_ERROR, LogEntry.LEVEL_FATAL
        )

        /** 需求：日志模块清单 */
        val MODULES = listOf(
            LogEntry.MODULE_UI, LogEntry.MODULE_AI, LogEntry.MODULE_YOLO,
            LogEntry.MODULE_SHIZUKU, LogEntry.MODULE_PROJECTION, LogEntry.MODULE_NET,
            LogEntry.MODULE_STORE, LogEntry.MODULE_PERM, LogEntry.MODULE_TASK,
            LogEntry.MODULE_GAME, LogEntry.MODULE_PLUGIN, LogEntry.MODULE_SKILL,
            LogEntry.MODULE_SYNC, LogEntry.MODULE_UPDATE, LogEntry.MODULE_SECURITY
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LogViewModel(NekoDatabase.get(NekoApp.get())) }
        }
    }
}
