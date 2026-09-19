package com.nekonyan.assistant.core.net

import org.json.JSONObject

/**
 * OpenAI 兼容响应解析（org.json，Android 与 Robolectric 下都有真实实现）。
 *
 * 流式（SSE）与非流式两种返回都要能解析：
 *   · 流式：`choices[0].delta.content`（逐字增量），reasoner 另给 `reasoning_content`；
 *   · 非流式：`choices[0].message.content`（整段），用于「测试连接」。
 * 任何解析失败都返回 null 而不是抛异常 —— 一个畸形分片不该让整轮对话崩掉。
 */
object DeepSeekResponse {

    /** 流式增量文本；空串视为"没有内容"（很多分片只带 role 或 finish_reason） */
    fun deltaText(payload: String): String? {
        return try {
            val delta = firstChoice(payload)?.optJSONObject("delta") ?: return null
            delta.optString("content", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 深度思考模型的思维链增量（deepseek-reasoner）；普通模型没有这个字段 */
    fun deltaReasoning(payload: String): String? {
        return try {
            val delta = firstChoice(payload)?.optJSONObject("delta") ?: return null
            delta.optString("reasoning_content", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 非流式整段回复 */
    fun messageText(payload: String): String? {
        return try {
            val msg = firstChoice(payload)?.optJSONObject("message") ?: return null
            msg.optString("content", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 结束原因：stop / length / content_filter 等 */
    fun finishReason(payload: String): String? {
        return try {
            val c = firstChoice(payload) ?: return null
            c.optString("finish_reason", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 错误体：OpenAI 风格的 {"error":{"message":...}} 与 {"error":"..."} 都吃 */
    fun errorMessage(payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            if (!obj.has("error")) return null
            when (val err = obj.opt("error")) {
                is JSONObject -> err.optString("message", "").ifEmpty { "服务端返回了未说明的错误" }
                is String -> err.ifEmpty { "服务端返回了未说明的错误" }
                else -> "服务端返回了未说明的错误"
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 用量（部分网关只在最后一帧给） */
    fun totalTokens(payload: String): Int? {
        return try {
            val usage = JSONObject(payload).optJSONObject("usage") ?: return null
            if (usage.has("total_tokens")) usage.optInt("total_tokens") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun firstChoice(payload: String): JSONObject? {
        val choices = JSONObject(payload).optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        return choices.optJSONObject(0)
    }
}
