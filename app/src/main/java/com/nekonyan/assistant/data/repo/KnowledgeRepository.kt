package com.nekonyan.assistant.data.repo

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

    suspend fun addTextItem(categoryId: String, text: String): String {
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
