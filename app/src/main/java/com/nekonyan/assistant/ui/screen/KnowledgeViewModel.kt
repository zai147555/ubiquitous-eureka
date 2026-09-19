package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.AIKnowledgeBase
import com.nekonyan.assistant.data.db.AIKnowledgeCategory
import com.nekonyan.assistant.data.db.AIKnowledgeItem
import com.nekonyan.assistant.data.db.KnowledgeBase
import com.nekonyan.assistant.data.db.KnowledgeCategory
import com.nekonyan.assistant.data.db.KnowledgeItem
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.repo.AIKnowledgeRepository
import com.nekonyan.assistant.data.repo.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 知识库页状态（需求：多个知识库、分类可独立开关、支持文本与图片、可导入导出）
 *
 * 结构固定为「知识库 → 分类 → 条目」三层，与 .ds 的
 * KnowledgeBase / KnowledgeCategory / KnowledgeItem 完全对应。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeViewModel(
    private val repo: KnowledgeRepository,
    private val aiRepo: AIKnowledgeRepository
) : ViewModel() {

    // ---------------- AI 知识库（修改.ds 第三项：用户只读、AI 可读写） ----------------

    val aiBase: StateFlow<AIKnowledgeBase?> = aiRepo.observeBase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aiCategories: StateFlow<List<AIKnowledgeCategory>> = aiRepo.observeBase()
        .flatMapLatest { base -> if (base == null) flowOf(emptyList()) else aiRepo.observeCategories(base.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedAiCategoryId = MutableStateFlow<String?>(null)
    val selectedAiCategoryId: StateFlow<String?> = _selectedAiCategoryId

    val aiItems: StateFlow<List<AIKnowledgeItem>> = _selectedAiCategoryId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else aiRepo.observeItems(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectAiCategory(id: String?) { _selectedAiCategoryId.value = id }

    /** 需求：AI 读取受设置开关控制，默认开启 */
    fun setAiReadable(enabled: Boolean) = viewModelScope.launch { aiRepo.setReadableByAI(enabled) }

    /** 用户查看 AI 知识库时留痕（与"AI 读取"区分开） */
    fun logAiView() = viewModelScope.launch { aiRepo.logUserView(aiItems.value.size) }

    val bases: StateFlow<List<KnowledgeBase>> = repo.observeBases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedBaseId = MutableStateFlow<String?>(null)
    val selectedBaseId: StateFlow<String?> = _selectedBaseId

    val categories: StateFlow<List<KnowledgeCategory>> = _selectedBaseId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeCategories(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId

    val items: StateFlow<List<KnowledgeItem>> = _selectedCategoryId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeItems(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectBase(id: String) {
        _selectedBaseId.value = id
        _selectedCategoryId.value = null
    }

    fun selectCategory(id: String) {
        _selectedCategoryId.value = id
    }

    fun createBase(name: String) = viewModelScope.launch {
        val id = repo.createBase(name)
        _selectedBaseId.value = id
        NekoLog.info(NekoLog.MODULE_STORE, "kb_create", name)
    }

    fun createCategory(name: String) {
        val baseId = _selectedBaseId.value ?: return
        viewModelScope.launch {
            val id = repo.createCategory(baseId, name)
            _selectedCategoryId.value = id
            NekoLog.info(NekoLog.MODULE_STORE, "kb_category_create", name)
        }
    }

    /** 需求：分类可独立开关 */
    fun toggleCategory(category: KnowledgeCategory) = viewModelScope.launch {
        repo.setCategoryEnabled(category.id, !category.enabledForAI)
        NekoLog.info(
            NekoLog.MODULE_STORE, "kb_category_toggle",
            "${category.name} → ${if (category.enabledForAI) "关闭" else "开启"}"
        )
    }

    fun deleteCategory(category: KnowledgeCategory) = viewModelScope.launch {
        repo.deleteCategory(category)
    }

    fun deleteBase(base: KnowledgeBase) = viewModelScope.launch {
        repo.deleteBase(base)
        if (_selectedBaseId.value == base.id) _selectedBaseId.value = null
    }

    fun addText(text: String) {
        val catId = _selectedCategoryId.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch { repo.addTextItem(catId, text.trim()) }
    }

    /** 需求：知识库支持文本图片（图片以 URI 保存，不复制大图进数据库） */
    fun addImage(uri: String, caption: String = "") {
        val catId = _selectedCategoryId.value ?: return
        viewModelScope.launch {
            repo.addImageItem(catId, uri, caption)
            NekoLog.info(NekoLog.MODULE_STORE, "kb_image_add", uri.takeLast(32))
        }
    }

    fun deleteItem(item: KnowledgeItem) = viewModelScope.launch { repo.deleteItem(item) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                KnowledgeViewModel(
                    repo = KnowledgeRepository(db.knowledgeDao()),
                    aiRepo = AIKnowledgeRepository(db.aiKnowledgeDao())
                )
            }
        }
    }
}
