package com.nekonyan.assistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO 集合。
 *
 * 约定：
 *   · 查询一律返回 Flow，UI 自动跟随数据变化（MVVM + Room + Flow，需求第 211 条）；
 *   · 日志 DAO **不提供 delete / update** —— 需求：日志只读，不可删除；
 *   · 其余 DAO 提供完整 CRUD，供单元测试覆盖。
 */

// ============================ 知识库 ============================

@Dao
interface KnowledgeDao {

    @Query("SELECT * FROM knowledge_base ORDER BY createdAt ASC")
    fun observeBases(): Flow<List<KnowledgeBase>>

    @Query("SELECT * FROM knowledge_base WHERE id = :id")
    suspend fun baseById(id: String): KnowledgeBase?

    @Upsert
    suspend fun upsertBase(base: KnowledgeBase)

    @Query("UPDATE knowledge_base SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: String, collapsed: Boolean)

    @Delete
    suspend fun deleteBase(base: KnowledgeBase)

    // ---- 分类 ----

    @Query("SELECT * FROM knowledge_category WHERE knowledgeBaseId = :baseId ORDER BY name ASC")
    fun observeCategories(baseId: String): Flow<List<KnowledgeCategory>>

    @Query("SELECT * FROM knowledge_category WHERE enabledForAI = 1")
    suspend fun categoriesForAI(): List<KnowledgeCategory>

    @Upsert
    suspend fun upsertCategory(category: KnowledgeCategory)

    /** 需求：分类可独立开关 */
    @Query("UPDATE knowledge_category SET enabledForAI = :enabled WHERE id = :id")
    suspend fun setCategoryEnabled(id: String, enabled: Boolean)

    @Delete
    suspend fun deleteCategory(category: KnowledgeCategory)

    // ---- 条目 ----

    @Query("SELECT * FROM knowledge_item WHERE categoryId = :categoryId ORDER BY createdAt DESC")
    fun observeItems(categoryId: String): Flow<List<KnowledgeItem>>

    @Query(
        """SELECT i.* FROM knowledge_item i
           INNER JOIN knowledge_category c ON c.id = i.categoryId
           WHERE c.enabledForAI = 1"""
    )
    suspend fun itemsForAI(): List<KnowledgeItem>

    @Upsert
    suspend fun upsertItem(item: KnowledgeItem)

    @Delete
    suspend fun deleteItem(item: KnowledgeItem)

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE categoryId = :categoryId")
    suspend fun countItems(categoryId: String): Int

    /** 需求：知识库整体导出/恢复 —— 导出全部内容 */
    @Transaction
    @Query("SELECT * FROM knowledge_base")
    suspend fun exportAll(): List<KnowledgeBase>
}

// ============================ AI 知识库 ============================

@Dao
interface AIKnowledgeDao {

    @Query("SELECT * FROM ai_knowledge_base LIMIT 1")
    suspend fun base(): AIKnowledgeBase?

    @Query("SELECT * FROM ai_knowledge_base LIMIT 1")
    fun observeBase(): Flow<AIKnowledgeBase?>

    @Upsert
    suspend fun upsertBase(base: AIKnowledgeBase)

    /** 需求：AI 知识库「初始不可管理」 */
    @Query("UPDATE ai_knowledge_base SET manageable = :manageable WHERE id = :id")
    suspend fun setManageable(id: String, manageable: Boolean)

    @Query("SELECT * FROM ai_knowledge_category WHERE aiKnowledgeBaseId = :baseId ORDER BY name ASC")
    fun observeCategories(baseId: String): Flow<List<AIKnowledgeCategory>>

    @Query("SELECT * FROM ai_knowledge_category WHERE enabledForWrite = 1 AND enabledForAI = 1")
    suspend fun writableCategories(): List<AIKnowledgeCategory>

    @Upsert
    suspend fun upsertCategory(category: AIKnowledgeCategory)

    @Query("SELECT * FROM ai_knowledge_item ORDER BY createdAt DESC")
    fun observeItems(): Flow<List<AIKnowledgeItem>>

    @Query("SELECT * FROM ai_knowledge_item WHERE categoryId = :categoryId ORDER BY createdAt DESC")
    fun observeItemsByCategory(categoryId: String): Flow<List<AIKnowledgeItem>>

    @Upsert
    suspend fun upsertItem(item: AIKnowledgeItem)

    @Delete
    suspend fun deleteItem(item: AIKnowledgeItem)

    /** 需求：去重合并 —— 按标题找已有条目 */
    @Query("SELECT * FROM ai_knowledge_item WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): AIKnowledgeItem?

    @Upsert
    suspend fun upsertImage(image: AIKnowledgeImage)

    @Query("SELECT * FROM ai_knowledge_image WHERE aiKnowledgeItemId = :itemId ORDER BY `order` ASC")
    suspend fun imagesOf(itemId: String): List<AIKnowledgeImage>
}

// ============================ 任务 ============================

@Dao
interface TaskDao {

    @Query("SELECT * FROM task ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Task>>

    /** 需求：各模式独立列表 */
    @Query("SELECT * FROM task WHERE mode = :mode ORDER BY createdAt DESC")
    fun observeByMode(mode: String): Flow<List<Task>>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byId(id: String): Task?

    @Upsert
    suspend fun upsert(task: Task)

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("UPDATE task SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 需求：任务列表显示最后执行时间与状态 */
    @Query("UPDATE task SET lastRunAt = :at, lastStatus = :status WHERE id = :id")
    suspend fun markRun(id: String, at: Long, status: String)

    @Query("SELECT COUNT(*) FROM task")
    suspend fun count(): Int
}

// ============================ 模式任务与权限 ============================

@Dao
interface ModeTaskDao {

    @Query("SELECT * FROM mode_task WHERE mode = :mode ORDER BY name ASC")
    fun observeTasks(mode: String): Flow<List<ModeTask>>

    @Upsert
    suspend fun upsert(task: ModeTask)

    @Delete
    suspend fun delete(task: ModeTask)

    @Query("SELECT * FROM mode_task_field WHERE mode = :mode ORDER BY fieldKey ASC")
    suspend fun fields(mode: String): List<ModeTaskField>

    @Upsert
    suspend fun upsertField(field: ModeTaskField)

    @Query("SELECT * FROM mode_task_permission WHERE mode = :mode LIMIT 1")
    suspend fun permission(mode: String): ModeTaskPermission?

    @Query("SELECT * FROM mode_task_permission WHERE mode = :mode LIMIT 1")
    fun observePermission(mode: String): Flow<ModeTaskPermission?>

    @Upsert
    suspend fun upsertPermission(permission: ModeTaskPermission)

    /**
     * 需求：AI 不得越权。执行前必须过这一关。
     * 返回 false 表示该模式未授予对应权限。
     */
    @Transaction
    suspend fun canAIExecute(mode: String): Boolean =
        permission(mode)?.allowAIExecute ?: false

    @Transaction
    suspend fun canAIWrite(mode: String, allowConfirm: Boolean): Boolean {
        val p = permission(mode) ?: return false
        if (!p.writeToAIKnowledge) return false
        return if (p.needConfirm) allowConfirm else true
    }
}

// ============================ AI 人格 ============================

@Dao
interface PersonaDao {

    @Query("SELECT * FROM ai_persona ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AIPersona>>

    @Query("SELECT * FROM ai_persona WHERE id = :id")
    suspend fun byId(id: String): AIPersona?

    @Query("SELECT * FROM ai_persona WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultPersona(): AIPersona?

    @Upsert
    suspend fun upsert(persona: AIPersona)

    @Delete
    suspend fun delete(persona: AIPersona)

    /** 需求：设为默认是互斥操作 */
    @Transaction
    suspend fun setDefault(id: String) {
        clearDefault()
        markDefault(id)
    }

    @Query("UPDATE ai_persona SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE ai_persona SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    /** 需求：使用时把「名称 + 描述」拼进系统提示词 */
    @Transaction
    suspend fun systemPromptFragment(personaId: String?): String {
        val p = personaId?.let { byId(it) } ?: default() ?: return ""
        return buildString {
            append("人格名称：").append(p.name)
            if (p.description.isNotBlank()) {
                append("\n人格描述：").append(p.description)
            }
        }
    }
}

// ============================ 会话与消息 ============================

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation_session ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ConversationSession>>

    @Query("SELECT * FROM conversation_session WHERE id = :id")
    suspend fun sessionById(id: String): ConversationSession?

    @Upsert
    suspend fun upsertSession(session: ConversationSession)

    @Query("UPDATE conversation_session SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Delete
    suspend fun deleteSession(session: ConversationSession)

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<Message>>

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentMessages(sessionId: String, limit: Int): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Query("UPDATE message SET compressed = 1 WHERE id IN (:ids)")
    suspend fun markCompressed(ids: List<String>)

    /** 需求：保留原文 N 天，其余可清理 */
    @Query("DELETE FROM message WHERE compressed = 1 AND createdAt < :before")
    suspend fun purgeCompressedBefore(before: Long): Int
}

// ============================ 上下文压缩配置 ============================

@Dao
interface ContextConfigDao {

    @Query("SELECT * FROM context_compression LIMIT 1")
    suspend fun config(): ContextCompression?

    @Query("SELECT * FROM context_compression LIMIT 1")
    fun observeConfig(): Flow<ContextCompression?>

    @Upsert
    suspend fun upsert(config: ContextCompression)
}

// ============================ 日志（★ 只读） ============================

@Dao
interface LogDao {

    @Insert
    suspend fun insert(entry: LogEntry)

    @Insert
    suspend fun insertAll(entries: List<LogEntry>)

    @Query("SELECT * FROM log_entry ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntry>>

    @Query(
        """SELECT * FROM log_entry
           WHERE (:level IS NULL OR level = :level)
             AND (:module IS NULL OR module = :module)
           ORDER BY timestamp DESC LIMIT :limit"""
    )
    fun observeFiltered(level: String?, module: String?, limit: Int = 500): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entry WHERE detail LIKE '%' || :keyword || '%' OR event LIKE '%' || :keyword || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(keyword: String, limit: Int = 200): List<LogEntry>

    @Query("SELECT COUNT(*) FROM log_entry")
    suspend fun count(): Int

    /** 需求：日志保留天数与大小上限可配 —— 轮转由日志写入方按策略调用 */
    @Query("DELETE FROM log_entry WHERE timestamp < :before")
    suspend fun rotateBefore(before: Long): Int

    // ★ 故意不提供 @Delete / @Update / 清空全部：需求要求日志只读，不可删除。
    //   仅允许按时间轮转（保留期策略），这属于容量管理而非"删除日志"。
}

// ============================ 插件 ============================

@Dao
interface PluginDao {

    @Query("SELECT * FROM plugin ORDER BY name ASC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugin WHERE enabled = 1")
    suspend fun enabled(): List<PluginEntity>

    @Query("SELECT * FROM plugin WHERE availableVersion IS NOT NULL AND availableVersion != version")
    fun observeUpdatable(): Flow<List<PluginEntity>>

    @Upsert
    suspend fun upsert(plugin: PluginEntity)

    @Delete
    suspend fun delete(plugin: PluginEntity)

    @Query("UPDATE plugin SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT grantedPermissions FROM plugin WHERE id = :id")
    suspend fun permissionsOf(id: String): List<String>?
}

// ============================ 环境检测 ============================

@Dao
interface EnvironmentDao {

    @Insert
    suspend fun insert(record: EnvironmentCheckRecord)

    @Query("SELECT * FROM environment_check_record ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<EnvironmentCheckRecord>>

    @Query("SELECT * FROM environment_check_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): EnvironmentCheckRecord?
}

// ============================ 运行模式 ============================

@Dao
interface RunModeDao {

    @Query("SELECT * FROM run_mode_config")
    fun observeAll(): Flow<List<RunModeConfig>>

    @Query("SELECT * FROM run_mode_config WHERE mode = :mode LIMIT 1")
    suspend fun byMode(mode: String): RunModeConfig?

    @Upsert
    suspend fun upsert(config: RunModeConfig)
}
