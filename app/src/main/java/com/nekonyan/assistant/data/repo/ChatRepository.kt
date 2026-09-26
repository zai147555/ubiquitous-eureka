package com.nekonyan.assistant.data.repo

import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.chat.PromptComposer
import com.nekonyan.assistant.core.chat.PromptMessage
import com.nekonyan.assistant.core.net.ChatOutcome
import com.nekonyan.assistant.core.net.DeepSeekClient
import com.nekonyan.assistant.core.net.DeepSeekVision
import com.nekonyan.assistant.data.db.ConversationDao
import com.nekonyan.assistant.data.db.ConversationSession
import com.nekonyan.assistant.data.db.Message
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import java.util.UUID

/**
 * 对话仓库：把「会话/消息持久化」与「网络流式请求」拼在一起。
 *
 * 设计取舍：流式增量**不逐字写库**（每次 delta 都写一次 Room 会明显拖慢并磨损闪存），
 * 而是先在内存里累积给界面看，一轮结束后才把完整回复落库一次；
 * 用户中途按紧急停止时，**已收到的部分照样落库**，不会白等一场。
 */
class ChatRepository(
    private val dao: ConversationDao,
    private val configStore: ChatConfigStore,
    private val client: DeepSeekClient = DeepSeekClient()
) {

    fun observeMessages(sessionId: String): Flow<List<Message>> = dao.observeMessages(sessionId)

    fun observeSessions(): Flow<List<ConversationSession>> = dao.observeSessions()

    suspend fun sessionById(id: String): ConversationSession? = dao.sessionById(id)

    // ---------------- 配置 ----------------

    fun config(): ChatConfig = configStore.load()

    fun saveConfig(config: ChatConfig) = configStore.save(config)

    fun hasApiKey(): Boolean = configStore.hasApiKey()

    /**
     * 云端识别一张图片（设置里开了「用 DS 云端识别图片」才会被调用）。
     * 放在仓库层是因为配置在这里（config()），界面层不该自己去找 API Key。
     */
    suspend fun describeImage(jpeg: ByteArray): DeepSeekVision.Result =
        DeepSeekVision.describe(config(), jpeg)

    fun clearApiKey() = configStore.clearApiKey()

    // ---------------- 会话与消息 ----------------

    /** 新建一个空会话（右上角 ＋） */
    suspend fun createSession(mode: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsertSession(ConversationSession(id = id, title = NEW_SESSION_TITLE, mode = mode))
        NekoLog.info(NekoLog.MODULE_UI, "chat_session_create", id.take(8))
        return id
    }

    /** 改名（空标题回退"新会话"，不允许出现没有名字的会话） */
    suspend fun renameSession(sessionId: String, title: String) {
        val session = dao.sessionById(sessionId) ?: return
        val t = title.trim().ifEmpty { NEW_SESSION_TITLE }
        dao.upsertSession(session.copy(title = t, updatedAt = System.currentTimeMillis()))
        NekoLog.info(NekoLog.MODULE_UI, "chat_session_rename", t)
    }

    /** 删除会话（消息随外键 CASCADE 一起删） */
    suspend fun deleteSession(sessionId: String) {
        dao.sessionById(sessionId)?.let {
            dao.deleteSession(it)
            NekoLog.info(NekoLog.MODULE_UI, "chat_session_delete", it.title)
        }
    }

    suspend fun latestSessionId(): String? = dao.latestSession()?.id   // 注意：DAO 返回的是实体，这里只要 id

    /** 没有会话就建一个（首启第一次进入聊天页时走这里） */
    suspend fun ensureSession(mode: String): String {
        dao.latestSession()?.let { return it.id }
        val id = UUID.randomUUID().toString()
        dao.upsertSession(ConversationSession(id = id, title = NEW_SESSION_TITLE, mode = mode))
        return id
    }

    suspend fun appendMessage(sessionId: String, role: String, content: String): Message {
        // ★ 外键保护（真机崩溃换来的）：
        //   message.sessionId → session.id 是 CASCADE 外键。会话被删掉之后
        //   （"删掉当前会话"，或**悬浮窗那份独立 ViewModel** 还攥着已被删除的 id），
        //   直接插消息会抛 SQLiteConstraintException(FOREIGN KEY) 并把 App 崩掉：
        //     FATAL: ConversationDao_Impl.insertMessage → FOREIGN KEY constraint failed
        //   所以：先确保会话行存在，再插；插失败也**不允许把进程带走**。
        ensureSessionRow(sessionId)
        val msg = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content
        )
        runCatching { dao.insertMessage(msg) }.onFailure { e ->
            NekoLog.error(NekoLog.MODULE_STORE, "insert_message_failed", e.javaClass.simpleName + ": " + e.message)
            // 可能是并发删除，补建后再试一次；仍失败就如实记日志（消息不落库，但不崩）
            runCatching {
                ensureSessionRow(sessionId)
                dao.insertMessage(msg)
            }.onFailure {
                NekoLog.error(NekoLog.MODULE_STORE, "insert_message_gave_up", "两次都失败：${it.message?.take(120)}")
            }
        }
        runCatching { dao.touch(sessionId, System.currentTimeMillis()) }
        return msg
    }

    /**
     * 确保会话行存在，不存在就补一条（标题用默认名）。
     * 用实体自带的 mode 默认值，不额外引入模式常量。
     */
    private suspend fun ensureSessionRow(sessionId: String) {
        if (sessionId.isBlank()) return
        if (runCatching { dao.sessionById(sessionId) }.getOrNull() != null) return
        runCatching {
            dao.upsertSession(ConversationSession(id = sessionId, title = NEW_SESSION_TITLE))
            NekoLog.warn(NekoLog.MODULE_STORE, "session_recreated", "会话不存在，已补建以免外键失败：$sessionId")
        }.onFailure {
            NekoLog.error(NekoLog.MODULE_STORE, "session_recreate_failed", it.javaClass.simpleName)
        }
    }

    /** 首条用户消息决定会话标题（之后不再改动，避免每轮都写库） */
    suspend fun renameSessionIfNeeded(sessionId: String, firstUserText: String) {
        val session = dao.sessionById(sessionId) ?: return
        if (session.title.isNotBlank() && session.title != NEW_SESSION_TITLE) return
        dao.upsertSession(
            session.copy(
                title = PromptComposer.sessionTitle(firstUserText),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 供模型使用的历史（正序，只看 user/assistant，压缩摘要等其它角色先跳过） */
    suspend fun promptHistory(
        sessionId: String,
        limit: Int = PromptComposer.DEFAULT_MAX_HISTORY
    ): List<PromptMessage> {
        val recent = dao.recentMessages(sessionId, limit * 2)
        return recent.asReversed().mapNotNull { m ->
            when (m.role) {
                Message.ROLE_USER -> PromptMessage.user(m.content)
                Message.ROLE_ASSISTANT -> PromptMessage.assistant(m.content)
                else -> null
            }
        }
    }

    // ---------------- 网络 ----------------

    fun ping(config: ChatConfig): ChatOutcome = client.ping(config)

    fun streamChat(
        config: ChatConfig,
        systemPrompt: String,
        history: List<PromptMessage>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onCallCreated: (Call) -> Unit = {},
        tools: List<com.nekonyan.assistant.core.agent.AgentTool> = emptyList()
    ): ChatOutcome {
        val messages = PromptComposer.compose(systemPrompt, history)
        return client.streamChat(
            config = config,
            messages = messages,
            onDelta = onDelta,
            onReasoning = onReasoning,
            onCallCreated = onCallCreated,
            tools = tools
        )
    }

    /**
     * 用**完整消息列表**发一次流式请求（Agent 循环用）。
     *
     * 与 [streamChat] 的区别：那条会自己用 PromptComposer 组装 system+history，
     * 而 Agent 循环的历史里包含 role=tool 与带 tool_calls 的 assistant 消息，
     * 必须原样送出、不能再被裁剪或重组。
     */
    fun streamWithMessages(
        config: ChatConfig,
        messages: List<PromptMessage>,
        tools: List<com.nekonyan.assistant.core.agent.AgentTool>,
        onDelta: (String) -> Unit,
        onCallCreated: (Call) -> Unit = {}
    ): ChatOutcome = client.streamChat(
        config = config,
        messages = messages,
        onDelta = onDelta,
        onCallCreated = onCallCreated,
        tools = tools
    )

    companion object {
        const val NEW_SESSION_TITLE = "新会话"
    }
}
