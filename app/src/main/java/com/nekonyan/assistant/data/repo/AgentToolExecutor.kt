package com.nekonyan.assistant.data.repo

import android.content.Context
import com.nekonyan.assistant.core.agent.ActionKind
import com.nekonyan.assistant.core.agent.AgentPolicy
import com.nekonyan.assistant.core.agent.ToolCall
import com.nekonyan.assistant.core.agent.ToolRegistry
import com.nekonyan.assistant.core.agent.ToolResult
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工具执行器（Agent 循环的手脚）。
 *
 * 三道门**按顺序**过，任一不过就返回一条说明给模型（而不是抛异常）：
 *   ① [ToolRegistry.validate]：未知工具 / 禁用名单（输入注入类）/ 空参数；
 *   ② 动作类确认：`ACT` 类工具在用户点头前不执行（[confirmed] 里没有就拒绝）；
 *   ③ 具体实现。
 *
 * 本轮**只接了两个**：
 *   · `now` —— 纯本地，无需任何权限与网络（也是验证整条 Agent 链路最安全的那个工具）；
 *   · 其余工具如实返回"尚未接入执行"，**不假装成功**——假成功会让模型基于幻觉继续推理，
 *     比直接失败难查得多。
 */
class AgentToolExecutor(
    private val context: Context,
    /** 用户已确认可执行的动作类工具名（由界面授权后传入） */
    private val confirmedTools: Set<String> = emptySet()
) {

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        // 门①：注册表校验（含禁用名单，优先于"是否存在"）
        ToolRegistry.validate(call)?.let { reason ->
            NekoLog.warn(NekoLog.MODULE_AI, "tool_rejected", "${ToolRegistry.describe(call)} → $reason")
            return@withContext ToolResult(call.id, call.name, reason, false)
        }
        val tool = ToolRegistry.find(call.name)!!
        if (AgentPolicy.isForbidden(call.name)) {
            return@withContext ToolResult(call.id, call.name, "该工具被策略禁止", false)
        }

        // 门②：动作类必须已确认
        if (tool.kind == ActionKind.ACT && call.name !in confirmedTools) {
            return@withContext ToolResult(
                call.id, call.name,
                "「${call.name}」会改动外界，需要用户确认后才能执行。请先用一句话告诉用户你要做什么，等确认。",
                false
            )
        }

        // 门③：执行
        val started = System.currentTimeMillis()
        val result = runCatching { dispatch(call) }.getOrElse {
            ToolResult(call.id, call.name, "工具执行异常：${it.javaClass.simpleName}: ${it.message}", false)
        }
        NekoLog.info(
            NekoLog.MODULE_AI, "tool_executed",
            "${ToolRegistry.describe(call)} → ${if (result.ok) "成功" else "失败"} 用时 ${System.currentTimeMillis() - started}ms"
        )
        result
    }

    private fun dispatch(call: ToolCall): ToolResult = when (call.name) {
        "now" -> ToolResult(call.id, call.name, nowText(), true)
        else -> ToolResult(
            call.id, call.name,
            "工具「${call.name}」尚未接入执行（当前只接通了 now）。请改用你已有的信息回答，或告诉用户这个能力还没做好。",
            false
        )
    }

    /** 时间：日期 + 时分 + 星期（模型自己没有时钟，很多"今天/现在"类问题要靠它） */
    private fun nowText(): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        val tz = java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT)
        return "现在时间：$date $time（$week，时区 $tz）"
    }

    /** 参数解析（org.json）：坏 JSON 不抛异常，交给调用方当"缺参数"处理 */
    private fun args(call: ToolCall): JSONObject =
        runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
}
