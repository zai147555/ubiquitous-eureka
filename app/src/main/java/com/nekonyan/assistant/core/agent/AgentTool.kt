package com.nekonyan.assistant.core.agent

/**
 * Agent 的工具模型（**纯 Kotlin**，可本机 kotlinc 断言）。
 *
 * 设计边界（本项目刻意如此，写在类型里而不是文档里）：
 *   · 工具分两类：[ActionKind.READ] 只读（查询/识别/浏览）与 [ActionKind.ACT] 会改动外界
 *     （打开应用/网页/系统操作）。**没有第三类** —— 输入注入类（Shizuku injectInputEvent、
 *     无障碍 performAction 操作别的应用）不进这个枚举，所以也不可能被模型调用到。
 *   · [ActionKind.ACT] 默认需要用户确认（[needsConfirm]），模型不能自己决定去点。
 */
enum class ActionKind { READ, ACT }

data class AgentTool(
    val name: String,
    val description: String,
    /** 参数 JSON Schema（对象型）原文 */
    val parametersJson: String,
    val kind: ActionKind = ActionKind.READ,
    /** 动作类是否默认需要确认（需求：有副作用的动作要用户点头） */
    val needsConfirm: Boolean = kind == ActionKind.ACT
)

/** 模型发起的一次工具调用 */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** 工具执行结果（回灌给模型的内容 + 成败，便于日志与反馈层） */
data class ToolResult(val callId: String, val name: String, val content: String, val ok: Boolean)

/** 一轮模型回复：要么是文本，要么是要调工具（也可能两者都有） */
data class AgentReply(val content: String?, val calls: List<ToolCall>) {
    val hasCalls: Boolean get() = calls.isNotEmpty()
}

/** 循环的下一步决策（纯函数，便于断言"什么时候该停、什么时候该继续"） */
sealed interface AgentStep {
    /** 直接给用户答案 */
    data class Finish(val text: String) : AgentStep
    /** 执行这些工具，然后把结果回灌继续 */
    data class UseTools(val calls: List<ToolCall>) : AgentStep
    /** 轮次超限（防止模型无限调工具把额度烧光） */
    data class GiveUp(val reason: String, val partialText: String) : AgentStep
}
