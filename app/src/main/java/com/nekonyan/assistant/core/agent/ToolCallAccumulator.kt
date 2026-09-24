package com.nekonyan.assistant.core.agent

/**
 * 流式 tool_calls 的一个增量分片。
 *
 * 由 `core/net/DeepSeekResponse` 从 SSE 分片里抽出来（那边用 org.json），
 * 本类因此保持**纯 Kotlin**，可本机 kotlinc 断言 —— 这是有意为之：
 * 流式工具调用的拼接规则最容易写错，而它在真机上表现为"工具名是空字符串"或
 * "arguments 不是合法 JSON"，排查成本极高。
 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String? = null
)

/**
 * 按 `index` 累积流式 tool_calls。
 *
 * 三条真实规则（不遵守就拼不出可用的调用）：
 *   ① `id` 与 `function.name` **只在第一片出现**，后续分片里是空的 —— 不能覆盖已有值；
 *   ② `function.arguments` 是**逐字符分片**到达的（`{"q":` / `"猫` / `粮"}`），必须按顺序追加；
 *   ③ `index` 用于区分同一次回复里的多个工具调用，且**可能出现顺序不保证**、甚至先出现参数后出现 id。
 *
 * `finish()` 按 index 升序产出，缺 id 时合成一个稳定 id（`call_<index>`），
 * 缺参数时给 `{}` —— 让上层拿到的永远是结构完整的调用，而不是半截数据。
 */
class ToolCallAccumulator {

    private val ids = LinkedHashMap<Int, String>()
    private val names = LinkedHashMap<Int, String>()
    private val args = LinkedHashMap<Int, StringBuilder>()

    fun feed(delta: ToolCallDelta) {
        delta.id?.takeIf { it.isNotEmpty() }?.let { ids[delta.index] = it }
        delta.name?.takeIf { it.isNotEmpty() }?.let { names[delta.index] = it }
        delta.argumentsFragment?.takeIf { it.isNotEmpty() }?.let {
            args.getOrPut(delta.index) { StringBuilder() }.append(it)
        }
    }

    /** 是否已经收到过任何分片（用于判断"这一轮是普通文本还是工具调用"） */
    val pending: Boolean get() = ids.isNotEmpty() || names.isNotEmpty() || args.isNotEmpty()

    fun finish(): List<ToolCall> =
        (ids.keys + names.keys + args.keys).distinct().sorted().map { i ->
            ToolCall(
                id = ids[i] ?: "call_$i",
                name = names[i].orEmpty(),
                argumentsJson = args[i]?.toString()?.takeIf { it.isNotBlank() } ?: "{}"
            )
        }

    fun reset() {
        ids.clear(); names.clear(); args.clear()
    }
}
