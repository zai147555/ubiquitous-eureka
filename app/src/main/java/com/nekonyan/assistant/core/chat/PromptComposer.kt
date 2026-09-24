package com.nekonyan.assistant.core.chat

/**
 * 一条要发给模型的消息（纯 Kotlin，与 Room 的 Message 实体解耦，便于独立测试）。
 *
 * function calling 需要两种"特殊消息"，所以多了两个可选字段：
 *   · assistant 消息可携带 [toolCallsJson]（模型要求调用工具）；
 *   · role="tool" 的消息必须带 [toolCallId]（把执行结果对应回那次调用）。
 * 普通对话两者都为 null，序列化结果与旧版**逐字节一致**。
 */
data class PromptMessage(
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null
) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"

        fun system(text: String) = PromptMessage(SYSTEM, text)
        fun user(text: String) = PromptMessage(USER, text)
        fun assistant(text: String) = PromptMessage(ASSISTANT, text)

        /** 模型要求调用工具的那条 assistant 消息 */
        fun assistantToolCalls(callsJson: String) =
            PromptMessage(ASSISTANT, "", toolCallsJson = callsJson)

        /** 工具执行结果（role=tool + tool_call_id） */
        fun toolResult(callId: String, content: String) =
            PromptMessage(TOOL, content, toolCallId = callId)
    }
}

/**
 * 提示词组装（**纯 Kotlin**）。
 *
 * 两件事最容易出错，所以都放在这里并被断言覆盖：
 *   ① 系统提示词里「人格（名称+描述）+ 运行模式 + 知识库」的拼接顺序，
 *      以及**关闭的知识库分类绝不出现**（需求：分类关闭后 AI 读不到）；
 *   ② 历史裁剪后**首条必须是 user** —— 若裁剪点正好落在 assistant 上，
 *      部分 OpenAI 兼容服务端会直接报 400。
 */
object PromptComposer {

    /** 默认携带的历史条数（需求里的上下文压缩上线前，先用轮数上限兜住） */
    const val DEFAULT_MAX_HISTORY = 24

    /** 知识库注入上限，避免知识库把上下文挤爆 */
    const val MAX_KNOWLEDGE_CHARS = 4000

    const val TITLE_MAX_CHARS = 18

    fun systemPrompt(
        personaName: String?,
        personaDescription: String?,
        modeLabel: String,
        knowledge: List<String> = emptyList()
    ): String = buildString {
        val name = personaName?.trim().orEmpty()
        val desc = personaDescription?.trim().orEmpty()

        append("你是「").append(name.ifEmpty { "猫娘助手" }).append("」。")
        if (desc.isNotEmpty()) append(desc).append('\n')
        else append("一个运行在 Android 手机上的个人助手。\n")

        append("当前运行模式：").append(modeLabel).append("\n\n")
        append("回答要求：\n")
        append("1. 用简体中文回答，直接给结论，不要客套。\n")
        append("2. 不确定就说不确定，不要编造事实。\n")
        append("3. 不要输出 API Key、Token、密码等敏感信息。\n")

        val items = knowledge.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isNotEmpty()) {
            append("\n以下是用户知识库中的内容，仅在相关时参考：\n")
            var used = 0
            for (item in items) {
                if (used >= MAX_KNOWLEDGE_CHARS) break
                val slice = item.take(MAX_KNOWLEDGE_CHARS - used)
                append("- ").append(slice.replace("\n", " ")).append('\n')
                used += slice.length
            }
        }
    }.trimEnd()

    /**
     * 组装最终消息列表：system + 裁剪后的历史。
     * @param history 按时间正序（旧 → 新）
     */
    fun compose(
        system: String,
        history: List<PromptMessage>,
        maxHistory: Int = DEFAULT_MAX_HISTORY
    ): List<PromptMessage> {
        val limit = if (maxHistory <= 0) DEFAULT_MAX_HISTORY else maxHistory
        val usable = history
            .filter { it.role != PromptMessage.SYSTEM && it.content.isNotBlank() }
            .takeLast(limit)

        // 裁剪后首条必须是 user：跳过开头连续的 assistant
        val firstUser = usable.indexOfFirst { it.role == PromptMessage.USER }
        val trimmed = if (firstUser < 0) emptyList() else usable.subList(firstUser, usable.size)

        return buildList {
            if (system.isNotBlank()) add(PromptMessage.system(system))
            addAll(trimmed)
        }
    }

    /** 会话标题：取首条用户消息的首行前 18 字 */
    fun sessionTitle(firstUserMessage: String): String {
        val line = firstUserMessage.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isEmpty()) return "新会话"
        return if (line.length <= TITLE_MAX_CHARS) line else line.take(TITLE_MAX_CHARS) + "…"
    }
}
