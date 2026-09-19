package com.nekonyan.assistant.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nekonyan.assistant.core.util.NekoMode

/**
 * 数据模型（字段严格对齐 猫娘助手.ds 第 423~465 行的实体清单）
 *
 * 命名规则：表名统一 snake_case，字段名与 .ds 保持一致（camelCase），
 * 便于需求与实现对读。M1 先落「主界面 + 侧边菜单」链路必需的实体，
 * 其余实体随对应里程碑追加（见 README「已实现 / 未实现」）。
 */

// ============================ 知识库 ============================

@Entity(tableName = "knowledge_base")
data class KnowledgeBase(
    @PrimaryKey val id: String,
    val name: String,
    val collapsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "knowledge_category",
    foreignKeys = [ForeignKey(
        entity = KnowledgeBase::class,
        parentColumns = ["id"], childColumns = ["knowledgeBaseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("knowledgeBaseId")]
)
data class KnowledgeCategory(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val name: String,
    /** 需求：分类可独立开关（关掉后 AI 不读该分类） */
    val enabledForAI: Boolean = true
)

/** 需求：知识库条目支持文本与图片 */
@Entity(
    tableName = "knowledge_item",
    foreignKeys = [ForeignKey(
        entity = KnowledgeCategory::class,
        parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId")]
)
data class KnowledgeItem(
    @PrimaryKey val id: String,
    val categoryId: String,
    /** text | image */
    val type: String = TYPE_TEXT,
    val textContent: String = "",
    val imageUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }
}

// ============================ AI 知识库 ============================

/** 需求：AI 知识库名为「AI」，初始不可管理 */
@Entity(tableName = "ai_knowledge_base")
data class AIKnowledgeBase(
    @PrimaryKey val id: String,
    val name: String,
    val manageable: Boolean = false,
    val readableByAI: Boolean = true,
    val writableByAI: Boolean = true
)

@Entity(
    tableName = "ai_knowledge_category",
    foreignKeys = [ForeignKey(
        entity = AIKnowledgeBase::class,
        parentColumns = ["id"], childColumns = ["aiKnowledgeBaseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("aiKnowledgeBaseId")]
)
data class AIKnowledgeCategory(
    @PrimaryKey val id: String,
    val aiKnowledgeBaseId: String,
    val name: String,
    val enabledForAI: Boolean = true,
    val enabledForWrite: Boolean = true
)

/** 需求：AI 写入前要总结出 标题/分类/摘要/关键步骤/注意事项/适用场景 */
@Entity(
    tableName = "ai_knowledge_item",
    foreignKeys = [ForeignKey(
        entity = AIKnowledgeCategory::class,
        parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId")]
)
data class AIKnowledgeItem(
    @PrimaryKey val id: String,
    val categoryId: String,
    val title: String,
    val summary: String = "",
    /** 关键步骤，每行一步 */
    val steps: String = "",
    val notes: String = "",
    /** 适用场景 */
    val scene: String = "",
    /** manual | ai | knowledge */
    val sourceType: String = SOURCE_AI,
    val sourceId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AI = "ai"
        const val SOURCE_KNOWLEDGE = "knowledge"
    }
}

@Entity(
    tableName = "ai_knowledge_image",
    foreignKeys = [ForeignKey(
        entity = AIKnowledgeItem::class,
        parentColumns = ["id"], childColumns = ["aiKnowledgeItemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("aiKnowledgeItemId")]
)
data class AIKnowledgeImage(
    @PrimaryKey val id: String,
    val aiKnowledgeItemId: String,
    val imageUri: String,
    val caption: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val order: Int = 0
)

// ============================ 任务 ============================

/**
 * 需求：任务必须填写「任务名称 + 任务消息」；点击任务自动发送该消息给 AI。
 * mode 取值见 [NekoMode]。
 */
@Entity(tableName = "task", indices = [Index("mode")])
data class Task(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    val message: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 需求：任务列表显示最后执行时间 */
    val lastRunAt: Long? = null,
    val lastStatus: String? = null
)

@Entity(tableName = "mode_task", indices = [Index("mode")])
data class ModeTask(
    @PrimaryKey val id: String,
    val mode: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true
)

@Entity(tableName = "mode_task_field", indices = [Index("mode")])
data class ModeTaskField(
    @PrimaryKey val id: String,
    val mode: String,
    val fieldKey: String,
    val label: String,
    /** text | number | bool | select */
    val type: String = "text",
    val defaultValue: String = "",
    val required: Boolean = false,
    val options: List<String> = emptyList()
)

/** 需求：每模式可设 AI 创建/修改/删除/执行权限、确认、日志、写入 AI 知识库 */
@Entity(tableName = "mode_task_permission", indices = [Index(value = ["mode"], unique = true)])
data class ModeTaskPermission(
    @PrimaryKey val id: String,
    val mode: String,
    val allowAICreate: Boolean = false,
    val allowAIModify: Boolean = false,
    val allowAIDelete: Boolean = false,
    val allowAIExecute: Boolean = false,
    val needConfirm: Boolean = true,
    val logEnabled: Boolean = true,
    val writeToAIKnowledge: Boolean = false
)

// ============================ AI 人格 ============================

/** 需求：AI 人格仅「名称 + 描述」，使用时加入系统提示词 */
@Entity(tableName = "ai_persona")
data class AIPersona(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ============================ 会话与上下文 ============================

@Entity(tableName = "conversation_session", indices = [Index("mode")])
data class ConversationSession(
    @PrimaryKey val id: String,
    val title: String = "",
    val mode: String = NekoMode.Default.key,
    val personaId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "message",
    foreignKeys = [ForeignKey(
        entity = ConversationSession::class,
        parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class Message(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** user | assistant | tool | system */
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 需求：消息可带图片（视觉模型识图） */
    val imageUri: String? = null,
    /** 该条是否被上下文压缩摘要替代 */
    val compressed: Boolean = false
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
        const val ROLE_SYSTEM = "system"
    }
}

/** 需求：上下文压缩可配 Token、轮数、保留、清理、继承 */
@Entity(tableName = "context_compression")
data class ContextCompression(
    @PrimaryKey val id: String,
    val enabled: Boolean = true,
    /** sliding_window | summary | key_facts | layered | hybrid */
    val method: String = "hybrid",
    val maxTokens: Int = 8000,
    val maxRounds: Int = 20,
    val keepOriginal: Boolean = true,
    val keepDays: Int = 30,
    val inheritAcrossModes: Boolean = false,
    val writeToAIKnowledge: Boolean = false
)

// ============================ 日志（★ 只读） ============================

/**
 * 需求：日志字段为「时间、级别、模块、事件、详情、设备 ID、会话 ID」；
 * **日志只读，不可删除** —— 因此 LogDao 只暴露 insert / query，不提供 delete/update。
 */
@Entity(
    tableName = "log_entry",
    indices = [Index("timestamp"), Index("level"), Index("module")]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** DEBUG | INFO | WARN | ERROR | FATAL */
    val level: String,
    /** UI | AI | YOLO | Shizuku | MediaProjection | NET | STORE | PERM | TASK | GAME | PLUGIN | SKILL | SYNC | UPDATE | SECURITY */
    val module: String,
    val event: String,
    val detail: String = "",
    val deviceId: String = "",
    val sessionId: String = ""
) {
    companion object {
        const val LEVEL_DEBUG = "DEBUG"
        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"
        const val LEVEL_FATAL = "FATAL"

        const val MODULE_UI = "UI"
        const val MODULE_AI = "AI"
        const val MODULE_YOLO = "YOLO"
        const val MODULE_SHIZUKU = "Shizuku"
        const val MODULE_PROJECTION = "MediaProjection"
        const val MODULE_NET = "NET"
        const val MODULE_STORE = "STORE"
        const val MODULE_PERM = "PERM"
        const val MODULE_TASK = "TASK"
        const val MODULE_GAME = "GAME"
        const val MODULE_PLUGIN = "PLUGIN"
        const val MODULE_SKILL = "SKILL"
        const val MODULE_SYNC = "SYNC"
        const val MODULE_UPDATE = "UPDATE"
        const val MODULE_SECURITY = "SECURITY"
    }
}

// ============================ 插件 ============================

/** 需求：插件页面含 列表/已安装/已启用/已禁用/可更新/日志；权限单独授权 */
@Entity(tableName = "plugin")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val packageName: String = "",
    val version: String = "",
    val enabled: Boolean = false,
    /** 需求：插件可改所有 UI */
    val canModifyUi: Boolean = false,
    /** 需求：不能破坏紧急停止、权限入口、日志只读、安全提示 */
    val grantedPermissions: List<String> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val availableVersion: String? = null
)

// ============================ 环境检测 ============================

/** 需求：一键诊断（权限、Shizuku、模型、网络、温度） */
@Entity(tableName = "environment_check_record")
data class EnvironmentCheckRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val checkType: String,
    /** ok | missing | degraded | error */
    val result: String,
    val missingItems: String = "",
    val degraded: Boolean = false
)

// ============================ 运行模式 ============================

/** 需求：视觉辅助 / 辅助输入 / 切换提示；未授权时回退 */
@Entity(tableName = "run_mode_config")
data class RunModeConfig(
    @PrimaryKey val id: String,
    val mode: String,
    val visualAssistEnabled: Boolean = true,
    val inputAssistEnabled: Boolean = false,
    /** 需求：Shizuku 未授权 → 回退视觉辅助 */
    val fallbackBehavior: String = "visual_only"
)
