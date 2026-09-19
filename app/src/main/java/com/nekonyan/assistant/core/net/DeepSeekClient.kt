package com.nekonyan.assistant.core.net

import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.chat.ChatJson
import com.nekonyan.assistant.core.chat.PromptMessage
import com.nekonyan.assistant.core.chat.SseFramer
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.log.Redactor
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 一轮请求的结果。
 *
 * Cancelled 与 Failure **刻意分开**：用户按了紧急停止不该显示成"出错了"，
 * 否则每次主动中断都要弹一条红色错误。
 */
sealed interface ChatOutcome {
    data class Success(val text: String) : ChatOutcome
    data class Failure(val message: String, val retryable: Boolean = true) : ChatOutcome
    data object Cancelled : ChatOutcome
}

/**
 * DeepSeek（OpenAI 兼容）对话客户端。
 *
 * 要点：
 *   · 流式走 SSE，增量通过 [streamChat] 的 onDelta 回吐，界面边收边显示；
 *   · 超时按配置**逐请求**设置（不污染全局 client）；
 *   · 取消 = `Call.cancel()`：协程取消**打不断阻塞中的 OkHttp 读**，
 *     所以把 Call 暴露给调用方，紧急停止时直接 cancel（这是真能立刻停住的唯一办法）；
 *   · 错误一律翻译成人话，并且**先过 Redactor 再进日志/界面**（错误体里可能回显 Key）。
 */
class DeepSeekClient(private val baseClient: OkHttpClient = defaultClient()) {

    /**
     * 流式对话。
     * @param onCallCreated 拿到 Call 后立刻回调，供调用方在"紧急停止"时 cancel
     */
    fun streamChat(
        config: ChatConfig,
        messages: List<PromptMessage>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onCallCreated: (Call) -> Unit = {}
    ): ChatOutcome {
        val c = config.normalized()
        c.blockingProblem()?.let { return ChatOutcome.Failure(it, retryable = false) }

        val bodyJson = ChatJson.chatRequestBody(
            messages = messages,
            model = c.model,
            stream = true,
            temperature = c.temperature,
            maxTokens = c.maxTokens
        )
        val call = newCall(c, bodyJson, stream = true)
        onCallCreated(call)
        NekoLog.info(
            NekoLog.MODULE_NET, "chat_request",
            c.describeForLog() + ", messages=${messages.size}"
        )

        return try {
            call.execute().use { resp ->
                val payload = resp.body
                if (!resp.isSuccessful) {
                    val snippet = runCatching { payload?.string().orEmpty() }.getOrDefault("")
                    NekoLog.error(
                        NekoLog.MODULE_NET, "chat_http_error",
                        "code=${resp.code} body=${Redactor.redact(snippet).take(200)}"
                    )
                    return ChatOutcome.Failure(
                        httpErrorText(resp.code, snippet),
                        retryable = resp.code >= 500 || resp.code == 429
                    )
                }
                if (payload == null) return ChatOutcome.Failure("服务端返回了空响应")
                when (val r = readStream(payload.source(), onDelta, onReasoning)) {
                    is ReadResult.Ok ->
                        if (r.text.isEmpty()) {
                            ChatOutcome.Failure(
                                "服务端没有返回内容：确认模型名（当前 ${c.model}）与账户额度",
                                retryable = false
                            )
                        } else {
                            NekoLog.info(NekoLog.MODULE_NET, "chat_done", "长度=${r.text.length}")
                            ChatOutcome.Success(r.text)
                        }
                    is ReadResult.Error -> ChatOutcome.Failure(r.message, retryable = false)
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) {
                NekoLog.warn(NekoLog.MODULE_NET, "chat_cancelled", "用户中断")
                ChatOutcome.Cancelled
            } else {
                NekoLog.error(NekoLog.MODULE_NET, "chat_io_error", e.javaClass.simpleName + ": " + e.message)
                ChatOutcome.Failure(networkErrorText(e), retryable = true)
            }
        } catch (e: Exception) {
            NekoLog.error(NekoLog.MODULE_NET, "chat_error", e.javaClass.simpleName + ": " + e.message)
            ChatOutcome.Failure("请求失败：${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
    }

    /** 「测试连接」：非流式、只要几个 token，用来验证 Key / 地址 / 模型是否可用 */
    fun ping(config: ChatConfig): ChatOutcome {
        val c = config.normalized()
        c.blockingProblem()?.let { return ChatOutcome.Failure(it, retryable = false) }

        val bodyJson = ChatJson.chatRequestBody(
            messages = listOf(PromptMessage.user("ping")),
            model = c.model,
            stream = false,
            temperature = 0.0,
            maxTokens = 8
        )
        val call = newCall(c, bodyJson, stream = false, timeoutSeconds = minOf(c.timeoutSeconds, 30))

        return try {
            call.execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ChatOutcome.Failure(httpErrorText(resp.code, payload), retryable = false)
                } else {
                    val text = DeepSeekResponse.messageText(payload)
                    if (text == null) {
                        ChatOutcome.Failure("已连通，但没有解析到回复内容（模型 ${c.model}）", retryable = false)
                    } else {
                        NekoLog.info(NekoLog.MODULE_NET, "chat_ping_ok", "model=${c.model}")
                        ChatOutcome.Success("连接正常，模型 ${c.model} 可用")
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) ChatOutcome.Cancelled else ChatOutcome.Failure(networkErrorText(e))
        } catch (e: Exception) {
            ChatOutcome.Failure("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- 内部 ----------------

    private sealed interface ReadResult {
        data class Ok(val text: String) : ReadResult
        data class Error(val message: String) : ReadResult
    }

    private fun readStream(
        source: BufferedSource,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): ReadResult {
        val framer = SseFramer()
        val sb = StringBuilder()

        fun consume(data: String) {
            DeepSeekResponse.deltaReasoning(data)?.let(onReasoning)
            DeepSeekResponse.deltaText(data)?.let {
                sb.append(it)
                onDelta(it)
            }
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            val event = framer.pushLine(line) ?: continue
            if (event.isDone) break
            DeepSeekResponse.errorMessage(event.data)?.let { return ReadResult.Error(it) }
            consume(event.data)
        }
        // 网关掐断时最后一帧可能没有空行收尾
        framer.flush()?.let { if (!it.isDone) consume(it.data) }
        return ReadResult.Ok(sb.toString())
    }

    private fun newCall(
        config: ChatConfig,
        bodyJson: String,
        stream: Boolean,
        timeoutSeconds: Int = config.timeoutSeconds
    ): Call {
        val client = baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(config.endpoint())
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request)
    }

    private fun httpErrorText(code: Int, rawBody: String): String {
        val hint = when (code) {
            400 -> "请求被拒绝（400）"
            401 -> "API Key 无效或已过期（401）"
            402 -> "账户余额不足（402）"
            403 -> "没有权限使用该模型（403）"
            404 -> "接口地址不对（404）：确认地址是 https://api.deepseek.com"
            422 -> "请求参数不被接受（422）"
            429 -> "请求过于频繁（429），稍后再试"
            in 500..599 -> "DeepSeek 服务端错误（$code），稍后重试"
            else -> "请求失败（$code）"
        }
        val serverMsg = DeepSeekResponse.errorMessage(rawBody)
        return if (serverMsg == null) hint else "$hint：${Redactor.redact(serverMsg).take(200)}"
    }

    private fun networkErrorText(e: IOException): String = when (e) {
        is UnknownHostException -> "域名解析失败：检查网络，或确认 API 地址填对了"
        is SocketTimeoutException -> "超时：网络较慢，可在配置里把超时调大"
        is SSLException -> "HTTPS 握手失败：可能是网络中间人证书问题，换网络再试"
        else -> "网络异常：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
