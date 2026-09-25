package com.nekonyan.assistant.data.repo

import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.KnowledgeCategory
import com.nekonyan.assistant.data.db.KnowledgeDao
import com.nekonyan.assistant.data.db.KnowledgeItem
import com.nekonyan.assistant.data.db.KnowledgeBase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 知识库仓库（需求：多个知识库，分类可独立开关，支持文本图片；
 * 支持导入、整体导出恢复）
 *
 * 仓库层职责：生成 id、保证不变量（如名称非空）、把"业务措辞"翻译成 DAO 调用。
 * ViewModel 只依赖本类，方便单元测试时替换。
 */
class KnowledgeRepository(private val dao: KnowledgeDao) {

    fun observeBases(): Flow<List<KnowledgeBase>> = dao.observeBases()

    fun observeCategories(baseId: String): Flow<List<KnowledgeCategory>> =
        dao.observeCategories(baseId)

    fun observeItems(categoryId: String): Flow<List<KnowledgeItem>> = dao.observeItems(categoryId)

    suspend fun createBase(name: String): String {
        val id = newId("kb")
        dao.upsertBase(KnowledgeBase(id = id, name = name.trim().ifBlank { "未命名知识库" }))
        return id
    }

    suspend fun renameBase(base: KnowledgeBase, name: String) =
        dao.upsertBase(base.copy(name = name.trim().ifBlank { base.name }))

    suspend fun setCollapsed(baseId: String, collapsed: Boolean) = dao.setCollapsed(baseId, collapsed)

    suspend fun deleteBase(base: KnowledgeBase) = dao.deleteBase(base)

    suspend fun createCategory(baseId: String, name: String): String {
        val id = newId("cat")
        dao.upsertCategory(
            KnowledgeCategory(
                id = id,
                knowledgeBaseId = baseId,
                name = name.trim().ifBlank { "未命名分类" }
            )
        )
        return id
    }

    /** 需求：分类可独立开关（关掉后 AI 不再读取该分类） */
    suspend fun setCategoryEnabled(categoryId: String, enabled: Boolean) =
        dao.setCategoryEnabled(categoryId, enabled)

    suspend fun deleteCategory(category: KnowledgeCategory) = dao.deleteCategory(category)

    /**
     * 确保分类行存在，否则给这个分类插条目会踩外键（knowledge_item.categoryId → category.id），
     * 抛 SQLiteConstraintException 并把 App 崩掉 —— Message 那张表已经真机崩过一次
     * （见 ChatRepository.appendMessage 的注释），这里是同一类问题的另一处入口：
     * 删掉分类之后界面若还拿着旧 id 去加条目就会命中。
     */
    private suspend fun ensureCategoryRow(categoryId: String) {
        if (categoryId.isBlank()) return
        if (runCatching { dao.categoryById(categoryId) }.getOrNull() != null) return
        // 注意：knowledgeBaseId 本身也是外键，**不能**随手填 "" —— 那样补建出来的分类
        // 自己就违规，runCatching 一吞，外键问题原样留着。所以先取一个真实的知识库 id。
        val baseId = runCatching { dao.anyBaseId() }.getOrNull()
        if (baseId.isNullOrBlank()) {
            NekoLog.error(NekoLog.MODULE_STORE, "category_guard_no_base", "库里没有知识库，无法补建分类：$categoryId")
            return
        }
        runCatching {
            dao.upsertCategory(
                // name 是必填字段（没有默认值），补建时也得给一个能认出来的名字
                KnowledgeCategory(id = categoryId, knowledgeBaseId = baseId, name = "已恢复的分类")
            )
            NekoLog.warn(NekoLog.MODULE_STORE, "category_recreated", "分类不存在，已补建以免外键失败：$categoryId")
        }.onFailure { NekoLog.error(NekoLog.MODULE_STORE, "category_recreate_failed", it.javaClass.simpleName) }
    }

    suspend fun addTextItem(categoryId: String, text: String): String {
        ensureCategoryRow(categoryId)
        val id = newId("item")
        dao.upsertItem(
            KnowledgeItem(
                id = id,
                categoryId = categoryId,
                type = KnowledgeItem.TYPE_TEXT,
                textContent = text
            )
        )
        return id
    }

    suspend fun addImageItem(categoryId: String, imageUri: String, caption: String = ""): String {
        ensureCategoryRow(categoryId)
        val id = newId("item")
        dao.upsertItem(
            KnowledgeItem(
                id = id,
                categoryId = categoryId,
                type = KnowledgeItem.TYPE_IMAGE,
                textContent = caption,
                imageUri = imageUri
            )
        )
        return id
    }

    suspend fun updateItem(item: KnowledgeItem) = dao.upsertItem(item)

    suspend fun deleteItem(item: KnowledgeItem) = dao.deleteItem(item)

    suspend fun countItems(categoryId: String): Int = dao.countItems(categoryId)

    /** 需求：知识库整体导出（此处返回结构快照，序列化由调用方决定格式） */
    suspend fun exportSnapshot(): List<KnowledgeBase> = dao.exportAll()

    private fun newId(prefix: String) = "$prefix-" + UUID.randomUUID().toString().take(8)
}
