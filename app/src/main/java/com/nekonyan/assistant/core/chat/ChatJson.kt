package com.nekonyan.assistant.core.chat

/**
 * 请求体构造（**纯 Kotlin**，手写序列化以便本机 kotlinc 断言）。
 *
 * 为什么不用序列化框架：本项目 Android 侧只有 OkHttp，没有引入 kotlinx-serialization；
 * 而请求体只有 5 个字段，手写的风险点集中在一处 —— **字符串转义**。
 * 因此把转义单独暴露成 [escape]，用断言把「引号/反斜杠/换行/控制字符」逐一钉死：
 * 少转义一个字符，用户消息里带个英文引号就会让整个请求 400。
 */
object ChatJson {

    /** JSON 字符串转义（不含首尾引号） */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (ch < ' ') sb.append("\\u" + ch.code.toString(16).padStart(4, '0'))
                    else sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun quoted(s: String): String = "\"" + escape(s) + "\""

    fun messagesJson(messages: List<PromptMessage>): String =
        messages.joinToString(separator = ",", prefix = "[", postfix = "]") { m ->
            "{\"role\":${quoted(m.role)},\"content\":${quoted(m.content)}}"
        }

    /** 数字要保持与语言环境无关（逗号小数点在某些地区会让服务端解析失败） */
    fun number(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /**
     * OpenAI 兼容的 chat/completions 请求体。
     * [stream] = true 时服务端返回 SSE。
     */
    fun chatRequestBody(
        messages: List<PromptMessage>,
        model: String,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int,
        /** 非空则带上 tools（function calling）；内容由 AgentJson.toolsSchema 生成 */
        toolsJson: String? = null
    ): String = buildString {
        append("{\"model\":").append(quoted(model))
        append(",\"messages\":").append(messagesJson(messages))
        append(",\"stream\":").append(stream)
        append(",\"temperature\":").append(number(temperature))
        append(",\"max_tokens\":").append(maxTokens)
        if (!toolsJson.isNullOrBlank()) append(",\"tools\":").append(toolsJson)
        append('}')
    }
}
