package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.data.db.AIPersona
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.repo.PersonaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonaUiState(
    val personas: List<AIPersona> = emptyList(),
    val currentId: String? = null,
    val currentName: String = "（未设置）",
    val message: String? = null
)

/** 人格页状态（`修改.ds` 第一项）：只做"名称 + 描述"两件事，不做别的字段 */
class PersonaViewModel(private val repo: PersonaRepository) : ViewModel() {

    private val _state = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { list ->
                val current = repo.currentPersona()
                _state.update {
                    it.copy(
                        personas = list,
                        currentId = current?.id,
                        currentName = current?.name ?: "（未设置）"
                    )
                }
            }
        }
    }

    fun create(name: String, description: String) = viewModelScope.launch {
        val created = repo.create(name, description)
        _state.update { it.copy(message = if (created == null) "人格名称不能为空" else "已新增「${created.name}」") }
    }

    fun update(persona: AIPersona, name: String, description: String) = viewModelScope.launch {
        repo.update(persona, name, description)
        _state.update { it.copy(message = "已保存「${name.trim()}」") }
    }

    fun duplicate(persona: AIPersona) = viewModelScope.launch {
        val copy = repo.duplicate(persona)
        _state.update { it.copy(message = "已复制为「${copy.name}」") }
    }

    fun delete(persona: AIPersona) = viewModelScope.launch {
        val error = repo.delete(persona)
        _state.update { it.copy(message = error ?: "已删除「${persona.name}」") }
    }

    fun setDefault(persona: AIPersona) = viewModelScope.launch {
        repo.setDefault(persona)
        _state.update { it.copy(message = "「${persona.name}」已设为默认") }
    }

    /** 需求：切换当前人格并记录日志（聊天/悬浮窗/任务都用它） */
    fun switchCurrent(persona: AIPersona) = viewModelScope.launch {
        repo.switchCurrent(persona)
        _state.update { it.copy(currentId = persona.id, currentName = persona.name, message = "已切换到「${persona.name}」") }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                PersonaViewModel(PersonaRepository(db.personaDao(), NekoApp.context()))
            }
        }
    }
}
