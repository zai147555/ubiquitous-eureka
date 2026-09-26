package com.nekonyan.assistant.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.chat.PromptComposer
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.net.ChatOutcome
import com.nekonyan.assistant.core.util.NekoMode
import com.nekonyan.assistant.data.db.Message
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.repo.ChatConfigStore
import com.nekonyan.assistant.data.repo.ChatRepository
import com.nekonyan.assistant.data.repo.PersonaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference
import com.nekonyan.assistant.core.chat.PromptMessage
import com.nekonyan.assistant.data.repo.AgentToolExecutor

/** 聊天页状态。消息本体以 Room 为准（单一数据源），这里只放"正在进行中"的临时量 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** 正在流式接收的回复（还没落库，落库发生在整轮结束） */
    val streamingText: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    /** 还没配好 Key/地址：界面据此提示去配置页 */
    val needConfig: Boolean = false,
    /** 正在执行的工具（界面上显示"正在调用 XX…"，让用户知道模型在干活而不是卡住） */
    val toolStatus: String? = null,
    /** 全部会话（右上角 ＋ 的管理面板用） */
    val sessions: List<com.nekonyan.assistant.data.db.ConversationSession> = emptyList(),
    val currentSessionId: String? = null,
    /** 动作类工具（如播放音乐）正等用户点头：界面据此弹确认框 */
    val pendingConfirm: PendingActConfirm? = null
)

/** 动作类工具的用户确认请求（summary 是给用户看的一句话，不含内部工具名） */
data class PendingActConfirm(val toolName: String, val summary: String)

/**
 * 聊天页 ViewModel。
 *
 * 三处刻意的设计：
 *   ① **消息以数据库为准**：界面订阅 Room 的 Flow，而不是自己维护一份列表 ——
 *      避免"界面显示的和库里存的不一致"这类幽灵问题；
 *   ② 流式增量只在内存里累积，整轮结束才落库一次（省闪存写，也让紧急停止有明确语义）；
 *   ③ 紧急停止 = `Call.cancel()`：协程取消打不断阻塞中的网络读，
 *      只有 cancel 掉 OkHttp 的 Call 才能**立刻**停下（否则要等到下一个数据包）。
 *      停止时已收到的部分照样落库，不让用户白等。
 */
class ChatViewModel(
    private val repo: ChatRepository,
    private val personas: PersonaRepository,
    /** Agent 循环的"手脚"：执行模型要求的工具调用 */
    private val toolExecutor: AgentToolExecutor
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val currentCall = AtomicReference<Call?>(null)
    private var streamJob: Job? = null
    private val _sessionId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var mode: NekoMode = NekoMode.Default

    init {
        // 动作类工具的用户确认只能在 init 里挂：executor 是在构造过程中建出来的，
        // 构造参数里引用 askActConfirm 会 "Unresolved reference"（没有 this 可捕获）。
        toolExecutor.confirmAct = { name, summary -> askActConfirm(name, summary) }

        viewModelScope.launch {
            val sid = repo.ensureSession(mode.key)
            _sessionId.value = sid
            _state.update { it.copy(needConfig = !repo.hasApiKey(), currentSessionId = sid) }
        }
        // 会话列表（管理面板）
        viewModelScope.launch {
            repo.observeSessions().collect { list -> _state.update { it.copy(sessions = list) } }
        }
        // 消息跟随"当前会话"切换：flatMapLatest 会在换会话时自动退订旧的
        viewModelScope.launch {
            _sessionId.filterNotNull().flatMapLatest { repo.observeMessages(it) }.collect { rows ->
                _state.update { s ->
                    s.copy(messages = rows.map { r ->
                        ChatMessage(
                            id = r.id, text = r.content,
                            fromUser = r.role == Message.ROLE_USER, timestamp = r.createdAt
                        )
                    })
                }
            }
        }
    }

    /** 当前会话 id（没有就建一个） */
    private suspend fun currentSession(): String =
        _sessionId.value ?: repo.ensureSession(mode.key).also {
            _sessionId.value = it
            _state.update { st -> st.copy(currentSessionId = it) }
        }

    /** 需求：右上角 ＋ 新建对话 */
    fun newConversation() = viewModelScope.launch {
        val sid = repo.createSession(mode.key)
        _sessionId.value = sid
        _state.update {
            it.copy(messages = emptyList(), streamingText = "", error = null, currentSessionId = sid)
        }
    }

    fun switchConversation(id: String) {
        if (id == _sessionId.value) return
        _sessionId.value = id
        _state.update { it.copy(messages = emptyList(), streamingText = "", error = null, currentSessionId = id) }
    }

    fun renameConversation(id: String, title: String) = viewModelScope.launch { repo.renameSession(id, title) }

    fun deleteConversation(id: String) = viewModelScope.launch {
        repo.deleteSession(id)
        if (_sessionId.value == id) {
            val next = repo.latestSessionId() ?: repo.createSession(mode.key)
            _sessionId.value = next
            _state.update { it.copy(messages = emptyList(), currentSessionId = next) }
        }
    }

    /** 从配置页返回聊天页时调用：让"还没配 Key"的提示及时消失 */
    fun refreshConfigFlag() {
        _state.update { it.copy(needConfig = !repo.hasApiKey()) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (_state.value.streaming) {
            _state.update { it.copy(error = "上一条还在生成：等它结束，或按紧急停止") }
            return
        }

        val config: ChatConfig = repo.config()
        val problem = config.blockingProblem()
        if (problem != null) {
            // 用户消息照样落库：不能让"打了字发出去却什么都没留下"
            viewModelScope.launch {
                val sid = currentSession()
                repo.appendMessage(sid, Message.ROLE_USER, trimmed)
                repo.renameSessionIfNeeded(sid, trimmed)
            }
            _state.update { it.copy(error = problem, needConfig = true) }
            NekoLog.warn(NekoLog.MODULE_UI, "chat_blocked", problem)
            return
        }

        streamJob = viewModelScope.launch {
            val sid = currentSession()
            repo.appendMessage(sid, Message.ROLE_USER, trimmed)
            repo.renameSessionIfNeeded(sid, trimmed)
            _state.update { it.copy(streaming = true, streamingText = "", error = null) }

            val persona = personas.currentPersona()
            val system = PromptComposer.systemPrompt(
                personaName = persona?.name,
                personaDescription = persona?.description,
                modeLabel = mode.label,
                knowledge = emptyList()      // 知识库改由 kb_search 工具按需检索
            )

            // ---------------- Agent 循环 ----------------
            // 一轮 = 调模型 → 若有 tool_calls 就执行并回灌 → 再调模型；最多 AgentPolicy.MAX_ROUNDS 轮
            val history = repo.promptHistory(sid).toMutableList()
            val prevCalls = mutableListOf<com.nekonyan.assistant.core.agent.ToolCall>()
            var round = 0
            var failure: String? = null
            var cancelled = false

            while (true) {
                val messages = buildList {
                    add(PromptMessage.system(system))
                    addAll(history)
                }
                val outcome = withContext(Dispatchers.IO) {
                    repo.streamWithMessages(
                        config = config,
                        messages = messages,
                        tools = com.nekonyan.assistant.core.agent.ToolRegistry.definitions,
                        onDelta = { delta ->
                            _state.update { it.copy(streamingText = it.streamingText + delta) }
                        },
                        onCallCreated = { call -> currentCall.set(call) }
                    )
                }
                currentCall.set(null)

                if (outcome is ChatOutcome.Failure) { failure = outcome.message; break }
                if (outcome is ChatOutcome.Cancelled) { cancelled = true; break }
                val success = outcome as ChatOutcome.Success
                if (success.calls.isEmpty()) break      // 没有工具调用 → 这就是最终回答

                val step = com.nekonyan.assistant.core.agent.AgentPolicy.next(
                    com.nekonyan.assistant.core.agent.AgentReply(success.text, success.calls),
                    round, prevCalls
                )
                if (step !is com.nekonyan.assistant.core.agent.AgentStep.UseTools) {
                    val why = (step as? com.nekonyan.assistant.core.agent.AgentStep.GiveUp)?.reason
                    if (why != null) _state.update { it.copy(error = why) }
                    break
                }
                round++
                // ① 把"模型要调工具"写进历史（arguments 必须是字符串，由 AgentJson 保证）
                history.add(
                    PromptMessage.assistantToolCalls(
                        com.nekonyan.assistant.core.agent.AgentJson.toolCallsArray(step.calls)
                    )
                )
                // ② 逐个执行，把结果按 tool_call_id 回灌
                step.calls.forEach { call ->
                    _state.update { it.copy(error = null, toolStatus = toolLabel(call.name)) }
                    NekoLog.info(NekoLog.MODULE_AI, "tool_call", "第 ${round} 轮：${call.name}")
                    val result = toolExecutor.execute(call)
                    history.add(PromptMessage.toolResult(result.callId, result.content))
                    prevCalls.add(call)
                }
                _state.update { it.copy(toolStatus = null) }
            }

            // ---------------- 收尾 ----------------
            // streamingText 累积了本次所有轮次的可见文本（模型可能先说"我查一下"再回答）
            val partial = _state.value.streamingText
            _state.update { it.copy(toolStatus = null) }
            when {
                failure != null -> {
                    if (partial.isNotBlank()) repo.appendMessage(sid, Message.ROLE_ASSISTANT, partial)
                    _state.update { it.copy(streaming = false, streamingText = "", error = failure) }
                    NekoLog.error(NekoLog.MODULE_UI, "chat_failed", failure)
                }
                cancelled -> {
                    if (partial.isNotBlank()) repo.appendMessage(sid, Message.ROLE_ASSISTANT, partial)
                    _state.update { it.copy(streaming = false, streamingText = "") }
                }
                partial.isNotBlank() -> {
                    repo.appendMessage(sid, Message.ROLE_ASSISTANT, partial)
                    _state.update { it.copy(streaming = false, streamingText = "") }
                }
                else -> _state.update { it.copy(streaming = false, streamingText = "") }
            }
        }
    }

    /**
     * 紧急停止（需求：始终有紧急停止按钮，音量键/通知栏也可触发）。
     * 拿到 Call 就 cancel 掉它 —— 请求会以 Cancelled 收场，已收到的内容照样落库。
     */
    fun stop() {
        // 有确认框挂着时先按"拒绝"放掉，否则工具层还在等一个永远不会来的答复
        confirmWaiter?.complete(false)
        confirmWaiter = null
        _state.update { it.copy(pendingConfirm = null) }
        val call = currentCall.getAndSet(null)
        if (call != null) {
            call.cancel()
            NekoLog.warn(NekoLog.MODULE_TASK, "emergency_stop", "已中断生成，保留已收到的内容")
        } else {
            streamJob?.cancel()
            streamJob = null
            _state.update { it.copy(streaming = false, streamingText = "") }
            NekoLog.warn(NekoLog.MODULE_TASK, "emergency_stop", "请求尚未发出，已取消")
        }
    }

    /**
     * 导入附件（底部「导入文件」「照片」）：复制到应用私有目录（长期可读，不受 URI 授权回收影响），
     * 并在会话里留一条可见记录。图片的视觉理解（deepseek-vl）属 M6 后续。
     */
    fun importAttachment(uri: android.net.Uri, isImage: Boolean) {
        viewModelScope.launch {
            val ctx = NekoApp.context()
            val name = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "未命名"
            val dir = java.io.File(ctx.filesDir, "imports").apply { mkdirs() }
            val target = java.io.File(dir, "${System.currentTimeMillis()}_$name")
            val ok = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: error("打开输入流失败")
            }.isSuccess
            val text = if (ok) {
                NekoLog.info(NekoLog.MODULE_STORE, if (isImage) "chat_photo_import" else "chat_file_import",
                    "$name ${target.length() / 1024}KB")
                val base = "${if (isImage) "🖼 已导入图片" else "📎 已导入文件"}：$name（已存入应用私有目录）"
                // 设置里开了「用 DS 云端识别图片」→ 立刻让云端看一眼，把描述并进同一条消息
                // （图片只能出现在 user 消息里，所以不能单独塞给模型当上下文）
                if (isImage && com.nekonyan.assistant.core.net.VisionSettings.enabled(ctx)) {
                    val shot = runCatching { target.readBytes() }.getOrNull()
                    if (shot == null) {
                        "$base\n（云端识别跳过：读不到文件）"
                    } else {
                        when (val r = repo.describeImage(shot)) {
                            is com.nekonyan.assistant.core.net.DeepSeekVision.Result.Ok ->
                                "$base\n【云端识别】${r.text}"
                            is com.nekonyan.assistant.core.net.DeepSeekVision.Result.Err ->
                                "$base\n（云端识别未成功：${r.message}）"
                        }
                    }
                } else {
                    base
                }
            } else {
                NekoLog.warn(NekoLog.MODULE_STORE, "chat_import_failed", name)
                "❌ 导入失败：$name"
            }
            val sid = currentSession()
            repo.appendMessage(sid, Message.ROLE_USER, text)
        }
    }

    /** 动作类工具的用户确认：界面点允许/拒绝后 complete */
    private var confirmWaiter: CompletableDeferred<Boolean>? = null

    /** 界面调用：允许/拒绝当前挂起的动作类工具 */
    fun answerConfirm(allow: Boolean) {
        confirmWaiter?.complete(allow)
        confirmWaiter = null
        _state.update { it.copy(pendingConfirm = null) }
    }

    /**
     * 工具层调用：弹确认框并等用户点头。
     *
     * 超时（[CONFIRM_TIMEOUT_MS]）或界面被销毁都按**拒绝**处理 ——
     * 不能让一次没被看见的确认框变成"默认同意"。
     */
    private suspend fun askActConfirm(toolName: String, summary: String): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        confirmWaiter = waiter
        _state.update { it.copy(pendingConfirm = PendingActConfirm(toolName, summary)) }
        return try {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.await() } ?: false
        } finally {
            if (confirmWaiter === waiter) confirmWaiter = null
            _state.update { it.copy(pendingConfirm = null) }
        }
    }

    /** 工具名 → 给用户看的动作描述（不要直接显示 kb_search 这种内部名） */
    private fun toolLabel(name: String): String = when (name) {
        "kb_search" -> "正在查知识库…"
        "now" -> "正在看时间…"
        "web_fetch" -> "正在读网页…"
        "yolo_detect_local" -> "正在本地识别图片…"
        "yolo_detect_service" -> "正在调用识别服务…"
        "music_play" -> "准备播放音乐…"
        else -> "正在执行工具…"
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        currentCall.getAndSet(null)?.cancel()
        super.onCleared()
    }

    companion object {
        /** 等用户确认动作类工具的上限：超时按**拒绝**处理（绝不默认同意） */
        const val CONFIRM_TIMEOUT_MS = 60_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                ChatViewModel(
                    repo = ChatRepository(
                        dao = db.conversationDao(),
                        configStore = ChatConfigStore(NekoApp.context())
                    ),
                    personas = PersonaRepository(db.personaDao(), NekoApp.context()),
                    toolExecutor = AgentToolExecutor(
                        context = NekoApp.context(),
                        knowledgeDao = db.knowledgeDao(),
                        aiKnowledgeDao = db.aiKnowledgeDao(),
                        // 让工具用「用户当前选中的模型」，和 yolo.ds 页保持一致
                        yoloModelDao = db.yoloModelDao()
                    )
                )
            }
        }
    }
}
