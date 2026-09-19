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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference

/** 聊天页状态。消息本体以 Room 为准（单一数据源），这里只放"正在进行中"的临时量 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** 正在流式接收的回复（还没落库，落库发生在整轮结束） */
    val streamingText: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    /** 还没配好 Key/地址：界面据此提示去配置页 */
    val needConfig: Boolean = false
)

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
    private val personas: PersonaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val currentCall = AtomicReference<Call?>(null)
    private var streamJob: Job? = null
    private var sessionId: String? = null
    private var mode: NekoMode = NekoMode.Default

    init {
        viewModelScope.launch {
            val sid = repo.ensureSession(mode.key)
            sessionId = sid
            _state.update { it.copy(needConfig = !repo.hasApiKey()) }
            repo.observeMessages(sid).collect { rows ->
                _state.update { s ->
                    s.copy(messages = rows.map { r ->
                        ChatMessage(
                            id = r.id,
                            text = r.content,
                            fromUser = r.role == Message.ROLE_USER,
                            timestamp = r.createdAt
                        )
                    })
                }
            }
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
                val sid = sessionId ?: repo.ensureSession(mode.key).also { sessionId = it }
                repo.appendMessage(sid, Message.ROLE_USER, trimmed)
                repo.renameSessionIfNeeded(sid, trimmed)
            }
            _state.update { it.copy(error = problem, needConfig = true) }
            NekoLog.warn(NekoLog.MODULE_UI, "chat_blocked", problem)
            return
        }

        streamJob = viewModelScope.launch {
            val sid = sessionId ?: repo.ensureSession(mode.key).also { sessionId = it }
            repo.appendMessage(sid, Message.ROLE_USER, trimmed)
            repo.renameSessionIfNeeded(sid, trimmed)
            _state.update { it.copy(streaming = true, streamingText = "", error = null) }

            val history = repo.promptHistory(sid)
            // 当前人格（`修改.ds` 第一项：当前人格用于聊天/悬浮窗/任务）
            val persona = personas.currentPersona()
            val system = PromptComposer.systemPrompt(
                personaName = persona?.name,
                personaDescription = persona?.description,
                modeLabel = mode.label,
                knowledge = emptyList()      // 知识库注入留到后续里程碑
            )

            val outcome = withContext(Dispatchers.IO) {
                repo.streamChat(
                    config = config,
                    systemPrompt = system,
                    history = history,
                    onDelta = { delta ->
                        _state.update { it.copy(streamingText = it.streamingText + delta) }
                    },
                    onCallCreated = { call -> currentCall.set(call) }
                )
            }
            currentCall.set(null)

            val partial = _state.value.streamingText
            when (outcome) {
                is ChatOutcome.Success -> {
                    repo.appendMessage(sid, Message.ROLE_ASSISTANT, outcome.text)
                    _state.update { it.copy(streaming = false, streamingText = "") }
                }
                is ChatOutcome.Failure -> {
                    if (partial.isNotBlank()) {
                        repo.appendMessage(sid, Message.ROLE_ASSISTANT, partial)
                    }
                    _state.update { it.copy(streaming = false, streamingText = "", error = outcome.message) }
                    NekoLog.error(NekoLog.MODULE_UI, "chat_failed", outcome.message)
                }
                ChatOutcome.Cancelled -> {
                    if (partial.isNotBlank()) {
                        repo.appendMessage(sid, Message.ROLE_ASSISTANT, partial)
                    }
                    _state.update { it.copy(streaming = false, streamingText = "") }
                }
            }
        }
    }

    /**
     * 紧急停止（需求：始终有紧急停止按钮，音量键/通知栏也可触发）。
     * 拿到 Call 就 cancel 掉它 —— 请求会以 Cancelled 收场，已收到的内容照样落库。
     */
    fun stop() {
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
                "${if (isImage) "🖼 已导入图片" else "📎 已导入文件"}：$name（已存入应用私有目录）"
            } else {
                NekoLog.warn(NekoLog.MODULE_STORE, "chat_import_failed", name)
                "❌ 导入失败：$name"
            }
            val sid = sessionId ?: repo.ensureSession(mode.key).also { sessionId = it }
            repo.appendMessage(sid, Message.ROLE_USER, text)
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        currentCall.getAndSet(null)?.cancel()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                ChatViewModel(
                    repo = ChatRepository(
                        dao = db.conversationDao(),
                        configStore = ChatConfigStore(NekoApp.context())
                    ),
                    personas = PersonaRepository(db.personaDao(), NekoApp.context())
                )
            }
        }
    }
}
