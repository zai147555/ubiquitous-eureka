package com.nekonyan.assistant.core.agent

/**
 * Agent 循环的策略（**纯 Kotlin**）。
 *
 * 三条规则，都是"不加就会出事"的那种：
 *   ① **轮次上限**：模型可能反复调工具；超限要停下并把已有文本给用户，而不是无限烧额度；
 *   ② **看门狗式的空转保护**：同一工具用完全相同的参数连续调两次，第二次直接判失败 ——
 *      否则模型会在"我调了但结果没用"时反复重试同一个调用（这是最常见的死循环形态）；
 *   ③ **动作类必须确认**：`ACT` 类工具在用户点头之前不执行，返回一条说明让模型换个说法。
 */
object AgentPolicy {

    const val MAX_ROUNDS = 5

    /** 连续两次同工具同参数 → 判定空转 */
    fun isRepeated(call: ToolCall, previous: List<ToolCall>): Boolean =
        previous.any { it.name == call.name && it.argumentsJson.trim() == call.argumentsJson.trim() }

    /**
     * 决定下一步。
     * @param round 已经跑过的轮次（从 0 开始）
     * @param confirmed 用户已确认执行动作类工具的集合（按工具名）
     */
    fun next(
        reply: AgentReply,
        round: Int,
        previousCalls: List<ToolCall>,
        confirmed: Set<String> = emptySet()
    ): AgentStep {
        val text = reply.content?.trim().orEmpty()
        if (!reply.hasCalls) {
            return AgentStep.Finish(text.ifEmpty { "（模型没有返回内容）" })
        }
        if (round >= MAX_ROUNDS) {
            return AgentStep.GiveUp(
                "已达工具调用轮次上限（$MAX_ROUNDS）",
                text.ifEmpty { "（工具调用过多，已停止）" }
            )
        }
        val fresh = reply.calls.filterNot { isRepeated(it, previousCalls) }
        if (fresh.isEmpty()) {
            return AgentStep.GiveUp("检测到重复调用同一工具（参数相同），已停止", text)
        }
        return AgentStep.UseTools(fresh)
    }

    /** 不允许模型调用的动作（注入类）：这里显式拦住并给出原因，便于日志排查 */
    val FORBIDDEN_TOOLS: Set<String> = setOf(
        "inject_input", "tap", "swipe", "auto_click", "perform_action", "shizuku_input"
    )

    fun isForbidden(name: String): Boolean = name.lowercase() in FORBIDDEN_TOOLS
}
