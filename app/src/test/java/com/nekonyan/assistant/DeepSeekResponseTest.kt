package com.nekonyan.assistant

import com.nekonyan.assistant.core.net.DeepSeekResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenAI 兼容响应解析测试。
 *
 * 用 Robolectric 是因为 `org.json` 在纯 JVM 单测里只是 android.jar 的桩
 * （本项目开了 `isReturnDefaultValues = true`，桩会静默返回 null，
 * 于是"解析失败"和"字段不存在"变得无法区分）。Robolectric 提供真实实现。
 *
 * 重点覆盖**畸形分片不能把整轮对话搞崩**：解析层一律返回 null，绝不抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DeepSeekResponseTest {

    @Test
    fun `解析流式增量`() {
        val payload = """{"choices":[{"delta":{"content":"你好"},"index":0}]}"""
        assertEquals("你好", DeepSeekResponse.deltaText(payload))
    }

    @Test
    fun `空内容与纯 role 分片返回 null`() {
        assertNull(DeepSeekResponse.deltaText("""{"choices":[{"delta":{"content":""}}]}"""))
        assertNull(DeepSeekResponse.deltaText("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(DeepSeekResponse.deltaText("""{"choices":[]}"""))
    }

    @Test
    fun `畸形分片不抛异常`() {
        assertNull(DeepSeekResponse.deltaText("不是 JSON"))
        assertNull(DeepSeekResponse.deltaText(""))
        assertNull(DeepSeekResponse.deltaText("{]"))
    }

    @Test
    fun `解析思维链增量`() {
        val payload = """{"choices":[{"delta":{"reasoning_content":"先算一下"}}]}"""
        assertEquals("先算一下", DeepSeekResponse.deltaReasoning(payload))
        assertNull(DeepSeekResponse.deltaReasoning("""{"choices":[{"delta":{"content":"x"}}]}"""))
    }

    @Test
    fun `解析非流式整段回复`() {
        val payload = """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""
        assertEquals("pong", DeepSeekResponse.messageText(payload))
        assertNull(DeepSeekResponse.messageText("""{"choices":[{"delta":{"content":"x"}}]}"""))
    }

    @Test
    fun `解析结束原因`() {
        assertEquals("stop", DeepSeekResponse.finishReason("""{"choices":[{"finish_reason":"stop"}]}"""))
        assertNull(DeepSeekResponse.finishReason("""{"choices":[{"delta":{"content":"x"}}]}"""))
    }

    @Test
    fun `错误体的两种形态都能认出来`() {
        assertEquals(
            "Invalid API key",
            DeepSeekResponse.errorMessage("""{"error":{"message":"Invalid API key","type":"authentication_error"}}""")
        )
        assertEquals(
            "额度不足",
            DeepSeekResponse.errorMessage("""{"error":"额度不足"}""")
        )
        assertNull("正常响应不应被当成错误", DeepSeekResponse.errorMessage("""{"choices":[{"delta":{"content":"x"}}]}"""))
        assertNull(DeepSeekResponse.errorMessage("不是 JSON"))
    }

    @Test
    fun `解析用量`() {
        assertEquals(42, DeepSeekResponse.totalTokens("""{"usage":{"total_tokens":42}}"""))
        assertNull(DeepSeekResponse.totalTokens("""{"choices":[]}"""))
    }
}
