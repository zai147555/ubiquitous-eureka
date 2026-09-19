package com.nekonyan.assistant.data.repo

import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.AIKnowledgeCategory
import com.nekonyan.assistant.data.db.AIKnowledgeDao
import com.nekonyan.assistant.data.db.AIKnowledgeImage
import com.nekonyan.assistant.data.db.AIKnowledgeItem
import java.util.UUID

/** 一次 AI 写入的结果：成功给条目 id，失败给人话原因 */
data class AIWriteResult(val ok: Boolean, val itemId: String? = null, val reason: String? = null)

/**
 * AI 知识库的**写入通道**（`修改.ds` 第三项）。
 *
 * 需求原文：AI 可读取（受设置开关控制，默认开启）；AI 可写入，**写入前先总结**，可附带截图。
 * 因此这里刻意不做"原文入库"：
 *   · 调用方必须给出 [title] 与 [summary]（即"先总结"这一步的产物），
 *     仓库层不接受一段未总结的长文 —— 否则 AI 知识库会变成聊天记录的垃圾场；
 *   · 同标题走 `findByTitle` **合并**而不是新增（需求：去重）；
 *   · 分类必须是 `enabledForWrite && enabledForAI` 的写入分类，否则拒绝并说明原因；
 *   · 只有 `readableByAI` 打开时读取通道才可用（读取开关在知识库页上）。
 *
 * 说明：模型的工具调用（tool calling）尚未接入，所以本类目前是**已就绪的通道**：
 * 开关、权限、去重、日志、截图附件都已按需求落地，接上模型调用即可生效。
 */
class AIKnowledgeRepository(private val dao: AIKnowledgeDao) {

    suspend fun base() = dao.base()

    // ---- 界面用的观察通道 ----

    fun observeBase() = dao.observeBase()

    fun observeCategories(baseId: String) = dao.observeCategories(baseId)

    fun observeItems(categoryId: String) = dao.observeItemsByCategory(categoryId)

    suspend fun itemsOf(categoryId: String): List<AIKnowledgeItem> = dao.allItems().filter { it.categoryId == categoryId }

    suspend fun imagesOf(itemId: String) = dao.imagesOf(itemId)

    /** 读取通道：开关关闭时返回空列表并记一条日志（日志只读，写进现有日志表） */
    suspend fun readForAI(): List<AIKnowledgeItem> {
        val base = dao.base() ?: return emptyList()
        if (!base.readableByAI) {
            NekoLog.info(NekoLog.MODULE_AI, "ai_kb_read_denied", "「AI 可读取」开关已关闭")
            return emptyList()
        }
        val items = dao.allItems()
        NekoLog.info(NekoLog.MODULE_AI, "ai_kb_read", "读取 ${items.size} 条")
        return items
    }

    /** 用户在界面上查看 AI 知识库时的留痕（与"AI 读取"区分开） */
    suspend fun logUserView(count: Int) {
        NekoLog.info(NekoLog.MODULE_AI, "ai_kb_view", "用户查看 AI 知识库：$count 条")
    }

    suspend fun setReadableByAI(enabled: Boolean): String? {
        val base = dao.base() ?: return "AI 知识库尚未初始化"
        dao.upsertBase(base.copy(readableByAI = enabled))
        NekoLog.info(NekoLog.MODULE_AI, "ai_kb_readable", if (enabled) "开启 AI 读取" else "关闭 AI 读取")
        return null
    }

    /**
     * AI 写入（需求：写入前先总结、可带截图、写日志）。
     * @param imageUris 截图附件（可空）
     */
    suspend fun writeFromAI(
        title: String,
        summary: String,
        steps: String = "",
        notes: String = "",
        scene: String = "",
        categoryId: String? = null,
        imageUris: List<String> = emptyList()
    ): AIWriteResult {
        val base = dao.base() ?: return AIWriteResult(false, reason = "AI 知识库尚未初始化")
        if (!base.writableByAI) return AIWriteResult(false, reason = "配置里已关闭「允许 AI 写入 AI 知识库」")

        val t = title.trim()
        if (t.isEmpty()) return AIWriteResult(false, reason = "标题不能为空")
        if (summary.isBlank()) return AIWriteResult(false, reason = "写入前必须先总结：summary 不能为空")

        val writable = dao.writableCategories()
        val category: AIKnowledgeCategory? = when {
            categoryId != null -> writable.firstOrNull { it.id == categoryId }
                ?: return AIWriteResult(false, reason = "分类不可写或未开启 AI 访问")
            writable.isNotEmpty() -> writable.first()
            else -> {
                // 没有任何可写分类时自动建一个，保证 AI 写不丢（并留痕）
                val created = AIKnowledgeCategory(
                    id = "ai-cat-" + UUID.randomUUID().toString().take(8),
                    aiKnowledgeBaseId = base.id,
                    name = "AI 自动总结",
                    enabledForAI = true,
                    enabledForWrite = true
                )
                dao.upsertCategory(created)
                NekoLog.info(NekoLog.MODULE_AI, "ai_kb_category_created", created.name)
                created
            }
        }

        // 需求：去重 —— 同标题合并而不是重复堆
        val existing = dao.findByTitle(t)
        val item = AIKnowledgeItem(
            id = existing?.id ?: ("ai-item-" + UUID.randomUUID().toString().take(8)),
            categoryId = category!!.id,
            title = t,
            summary = summary.trim(),
            steps = steps.trim().ifEmpty { existing?.steps.orEmpty() },
            notes = notes.trim().ifEmpty { existing?.notes.orEmpty() },
            scene = scene.trim().ifEmpty { existing?.scene.orEmpty() },
            sourceType = AIKnowledgeItem.SOURCE_AI,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        dao.upsertItem(item)

        imageUris.filter { it.isNotBlank() }.forEachIndexed { index, uri ->
            dao.upsertImage(
                AIKnowledgeImage(
                    id = "ai-img-" + UUID.randomUUID().toString().take(8),
                    aiKnowledgeItemId = item.id,
                    imageUri = uri,
                    order = index
                )
            )
        }

        NekoLog.info(
            NekoLog.MODULE_AI, if (existing != null) "ai_kb_merge" else "ai_kb_write",
            "「${item.title}」分类=${category.name} 截图=${imageUris.size}"
        )
        return AIWriteResult(true, itemId = item.id)
    }
}
