package com.nekonyan.assistant.core.chat

/** 一个 SSE 事件。聊天流只用到 `data` 字段（event/id/retry 对增量输出无意义） */
data class SseEvent(val data: String) {
    /** OpenAI 兼容接口用 `data: [DONE]` 表示流结束 */
    val isDone: Boolean get() = data.trim() == DONE

    companion object {
        const val DONE = "[DONE]"
    }
}

/**
 * SSE 分帧器（**纯 Kotlin**，可本机 kotlinc 编译并断言）。
 *
 * 逐行喂入（调用方用 OkHttp 的 `readUtf8Line()`，已去掉行尾换行），
 * **空行**表示一个事件结束 —— 这是 SSE 规范里唯一的分帧依据。
 *
 * 刻意处理了这几个真实会踩的点：
 *   · 多行 `data:` 必须用 `\n` 连接成**一个**事件（不能拆成多个事件）；
 *   · 字段名与值之间**只去掉一个**空格（`data: x` → `x`，而 `data:  x` → ` x`）；
 *   · `:` 开头是注释/心跳（部分网关会发），必须忽略而不是当数据；
 *   · CRLF 行尾要能兼容（`\r` 混进 JSON 里会解析失败）；
 *   · 流被掐断时最后一帧可能没有空行收尾，所以提供 [flush]。
 */
class SseFramer {

    private val dataLines = ArrayList<String>()

    /** 是否还有未派发的数据（用于排查「流断了但没报错」这类问题） */
    val hasPending: Boolean get() = dataLines.isNotEmpty()

    /** 喂入一行；返回非 null 表示一个完整事件已就绪 */
    fun pushLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")

        // 空行 = 事件边界
        if (line.isEmpty()) return dispatch()

        // 注释 / 心跳
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        if (field == "data") dataLines.add(value)
        // event / id / retry 一律忽略
        return null
    }

    /** 流结束时调用，派发最后可能残留的一帧 */
    fun flush(): SseEvent? = dispatch()

    fun reset() = dataLines.clear()

    private fun dispatch(): SseEvent? {
        if (dataLines.isEmpty()) return null
        val event = SseEvent(dataLines.joinToString("\n"))
        dataLines.clear()
        return event
    }
}
