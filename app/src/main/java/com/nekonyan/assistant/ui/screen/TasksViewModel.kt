package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.db.NekoMode
import com.nekonyan.assistant.data.db.Task
import com.nekonyan.assistant.data.repo.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.nekonyan.assistant.core.util.NekoMode

/**
 * 任务页状态。
 *
 * 需求要点体现在接口上：
 *   · 按模式分独立列表（切换模式即切换数据源）；
 *   · 点击任务自动发送消息（[consumePendingMessage]）；
 *   · 任务列显示最后执行时间与状态（由 Task.lastRunAt / lastStatus 提供）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(private val repo: TaskRepository) : ViewModel() {

    private val _mode = MutableStateFlow(NekoMode.Default)
    val mode: StateFlow<NekoMode> = _mode

    val tasks: StateFlow<List<Task>> = _mode
        .flatMapLatest { repo.observeByMode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 待发送的消息：界面消费后置空（避免重组时重复发送） */
    private val _pendingMessage = MutableStateFlow<String?>(null)
    val pendingMessage: StateFlow<String?> = _pendingMessage

    /** 出错提示（例如名称为空），界面弹一次即清 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setMode(m: NekoMode) { _mode.value = m }

    fun create(name: String, message: String) = viewModelScope.launch {
        try {
            repo.create(_mode.value, name, message)
            NekoLog.info(NekoLog.MODULE_TASK, "task_create", "$name（${_mode.value.label}）")
        } catch (e: IllegalArgumentException) {
            _error.value = e.message
            NekoLog.warn(NekoLog.MODULE_TASK, "task_create_rejected", e.message ?: "")
        }
    }

    fun setEnabled(task: Task, enabled: Boolean) = viewModelScope.launch {
        repo.setEnabled(task.id, enabled)
        NekoLog.info(NekoLog.MODULE_TASK, "task_enabled", "${task.name} → $enabled")
    }

    fun delete(task: Task) = viewModelScope.launch {
        repo.delete(task)
        NekoLog.info(NekoLog.MODULE_TASK, "task_delete", task.name)
    }

    /** 需求：点击任务自动把任务消息发给 AI，无需手动输入 */
    fun run(task: Task) = viewModelScope.launch {
        val msg = repo.renderMessage(task)
        _pendingMessage.value = msg
        repo.markRun(task.id, "已发送")
        NekoLog.info(NekoLog.MODULE_TASK, "task_run", "${task.name} → ${msg.take(60)}")
    }

    fun consumePendingMessage() { _pendingMessage.value = null }
    fun clearError() { _error.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                TasksViewModel(TaskRepository(db.taskDao()))
            }
        }
    }
}
