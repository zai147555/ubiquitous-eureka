package com.nekonyan.assistant.core.agent

/**
 * Agent 相关的 JSON 构造（**纯 Kotlin**，与 core/chat/ChatJson 同风格：手写转义 + 可断言）。
 *
 * 三样东西是 function calling 的最小集：
 *   ① `tools` 数组（告诉模型有哪些工具）；
 *   ② assistant 消息里的 `tool_calls`（模型要调什么）；
 *   ③ `role:"tool"` 的结果消息（把执行结果回灌，`tool_call_id` 必须与调用 id 一致，否则服务端 400）。
 */
object AgentJson {

    fun toolsSchema(tools: List<AgentTool>): String =
        tools.joinToString(separator = ",", prefix = "[", postfix = "]") { t ->
            buildString {
                append("{\"type\":\"function\",\"function\":{")
                append("\"name\":").append(quote(t.name)).append(',')
                append("\"description\":").append(quote(t.description)).append(',')
                append("\"parameters\":").append(t.parametersJson)
                append("}}")
            }
        }

    /** 只产出 tool_calls 数组本身（写进历史时用；消息体构造也复用它） */
    fun toolCallsArray(calls: List<ToolCall>): String =
        calls.joinToString(separator = ",", prefix = "[", postfix = "]") { c ->
            buildString {
                append("{\"id\":").append(quote(c.id))
                append(",\"type\":\"function\",\"function\":{")
                append("\"name\":").append(quote(c.name))
                append(",\"arguments\":").append(quote(c.argumentsJson))
                append("}}")
            }
        }

    fun assistantToolCallMessage(content: String?, calls: List<ToolCall>): String = buildString {
        append("{\"role\":\"assistant\",\"content\":")
        append(if (content.isNullOrEmpty()) "null" else quote(content))
        append(",\"tool_calls\":")
        append(toolCallsArray(calls))
        append('}')
    }

    fun toolResultMessage(result: ToolResult): String = buildString {
        append("{\"role\":\"tool\",\"tool_call_id\":").append(quote(result.callId))
        append(",\"content\":").append(quote(result.content))
        append('}')
    }

    fun quote(s: String): String = "\"" + com.nekonyan.assistant.core.chat.ChatJson.escape(s) + "\""
}
