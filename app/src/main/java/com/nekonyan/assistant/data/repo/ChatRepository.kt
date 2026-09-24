package com.nekonyan.assistant.data.repo

import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.chat.PromptComposer
import com.nekonyan.assistant.core.chat.PromptMessage
import com.nekonyan.assistant.core.net.ChatOutcome
import com.nekonyan.assistant.core.net.DeepSeekClient
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

    suspend fun latestSessionId(): String? = dao.latestSession()

    /** 没有会话就建一个（首启第一次进入聊天页时走这里） */
    suspend fun ensureSession(mode: String): String {
        dao.latestSession()?.let { return it.id }
        val id = UUID.randomUUID().toString()
        dao.upsertSession(ConversationSession(id = id, title = NEW_SESSION_TITLE, mode = mode))
        return id
    }

    suspend fun appendMessage(sessionId: String, role: String, content: String): Message {
        val msg = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content
        )
        dao.insertMessage(msg)
        dao.touch(sessionId, System.currentTimeMillis())
        return msg
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
        onCallCreated: (Call) -> Unit = {}
    ): ChatOutcome {
        val messages = PromptComposer.compose(systemPrompt, history)
        return client.streamChat(
            config = config,
            messages = messages,
            onDelta = onDelta,
            onReasoning = onReasoning,
            onCallCreated = onCallCreated
        )
    }

    companion object {
        const val NEW_SESSION_TITLE = "新会话"
    }
}
