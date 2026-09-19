package com.nekonyan.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.nekonyan.assistant.core.util.NekoMode

/**
 * 应用数据库。
 *
 * 首启种子数据（需求原文）：
 *   · AI 知识库名为「AI」，**初始不可管理**（manageable = false）；
 *   · 每个模式（只聊 / 聊天可控 / 伴随 / 自动游戏 / 后台网页 / 视频控制 / 插件）
 *     预置一条权限记录，默认「需要确认、写 AI 知识库关闭」——即最保守的默认值，
 *     用户显式开启后 AI 才有写权限（需求：不允许越权）。
 */
@Database(
    entities = [
        KnowledgeBase::class, KnowledgeCategory::class, KnowledgeItem::class,
        AIKnowledgeBase::class, AIKnowledgeCategory::class,
        AIKnowledgeItem::class, AIKnowledgeImage::class,
        Task::class, ModeTask::class, ModeTaskField::class, ModeTaskPermission::class,
        AIPersona::class, ConversationSession::class, Message::class, ContextCompression::class,
        LogEntry::class, PluginEntity::class, EnvironmentCheckRecord::class, RunModeConfig::class,
        // yolo.ds 第 124~130 行：模型管理的 8 张表
        YoloModelEntity::class, YoloModelVersionEntity::class, YoloModelSwitchRecordEntity::class,
        YoloModelImportRecordEntity::class, YoloModelExportRecordEntity::class,
        YoloModelUpdateRecordEntity::class, YoloModelPerformanceEntity::class, YoloModelConfigEntity::class
    ],
    version = 2,
    // 关闭 schema 导出：本工程尚无迁移需求。开启时需要确保 schemas/ 目录
    // 已存在且随仓库提交，否则 Room 会报 "Empty schema file"（CI 上踩过这个坑）。
    // 将来做数据库迁移时：改成 true + ksp arg room.schemaLocation + 提交 app/schemas/。
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NekoDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun aiKnowledgeDao(): AIKnowledgeDao
    abstract fun taskDao(): TaskDao
    abstract fun modeTaskDao(): ModeTaskDao
    abstract fun personaDao(): PersonaDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contextConfigDao(): ContextConfigDao
    abstract fun logDao(): LogDao
    abstract fun pluginDao(): PluginDao
    abstract fun environmentDao(): EnvironmentDao
    abstract fun runModeDao(): RunModeDao
    abstract fun yoloModelDao(): YoloModelDao

    companion object {
        const val DB_NAME = "nekonyan.db"
        const val AI_KB_ID = "ai-kb"
        const val DEFAULT_PERSONA_ID = "persona-default"

        @Volatile
        private var instance: NekoDatabase? = null

        /** 数据库写入串行化：所有写操作都走这个单线程调度器，避免并发事务冲突 */
        val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

        fun get(context: Context): NekoDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): NekoDatabase =
            Room.databaseBuilder(context, NekoDatabase::class.java, DB_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // v1 → v2 只是**新增**了 YOLO 模型管理的 8 张表。
                // 手写迁移的 SQL 必须与 Room 生成的建表语句逐字节一致，写错的表现是
                // 打开数据库时抛 IllegalStateException（升级即崩），而收益只是保住
                // 测试期的聊天记录 —— 因此在 0.2.x 阶段选择重建：数据清空，但一定起得来。
                // 上线前若要保数据：开启 exportSchema + 提交 schemas/ + 写正式 Migration。
                .fallbackToDestructiveMigration()
                .build()

        /**
         * 首启种子数据，幂等（可重复调用，不会重复插入）。
         * 在 Application 启动时于 [writeDispatcher] 上调用一次。
         */
        suspend fun seed(db: NekoDatabase) {
            // ---- AI 知识库：名「AI」，初始不可管理 ----
            if (db.aiKnowledgeDao().base() == null) {
                db.aiKnowledgeDao().upsertBase(
                    AIKnowledgeBase(
                        id = AI_KB_ID,
                        name = "AI",
                        manageable = false,
                        readableByAI = true,
                        writableByAI = true
                    )
                )
            }

            // ---- 默认人格（需求：AI 人格仅名称 + 描述） ----
            if (db.personaDao().defaultPersona() == null) {
                db.personaDao().upsert(
                    AIPersona(
                        id = DEFAULT_PERSONA_ID,
                        name = "猫娘",
                        description = "一只好奇、话少、愿意帮忙的橘猫助手。回答简洁，必要时才展开步骤。",
                        isDefault = true
                    )
                )
            }

            // ---- 各模式权限：保守默认（需确认、不自动写 AI 知识库） ----
            NekoMode.entries.forEach { mode ->
                if (db.modeTaskDao().permission(mode.key) == null) {
                    db.modeTaskDao().upsertPermission(
                        ModeTaskPermission(
                            id = "perm-${mode.key}",
                            mode = mode.key,
                            allowAICreate = false,
                            allowAIModify = false,
                            allowAIDelete = false,
                            allowAIExecute = false,
                            needConfirm = true,
                            logEnabled = true,
                            writeToAIKnowledge = false
                        )
                    )
                }
            }

            // ---- 运行模式：默认「视觉辅助」（需求：默认只提示，不操作） ----
            if (db.runModeDao().byMode("visual") == null) {
                db.runModeDao().upsert(
                    RunModeConfig(
                        id = "runmode-visual",
                        mode = "visual",
                        visualAssistEnabled = true,
                        inputAssistEnabled = false,
                        fallbackBehavior = "visual_only"
                    )
                )
                db.runModeDao().upsert(
                    RunModeConfig(
                        id = "runmode-input",
                        mode = "input",
                        visualAssistEnabled = true,
                        inputAssistEnabled = true,
                        fallbackBehavior = "visual_only"
                    )
                )
            }

            // ---- 上下文压缩默认策略 ----
            if (db.contextConfigDao().config() == null) {
                db.contextConfigDao().upsert(ContextCompression(id = "ctx-default"))
            }
        }

        /** 便捷入口：在后台串行执行种子数据 */
        fun seedAsync(context: Context, scope: CoroutineScope) {
            scope.launch(writeDispatcher) {
                runCatching { seed(get(context)) }
            }
        }
    }
}
