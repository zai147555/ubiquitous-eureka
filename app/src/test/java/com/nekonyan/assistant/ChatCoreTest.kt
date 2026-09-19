package com.nekonyan.assistant

import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.chat.ChatJson
import com.nekonyan.assistant.core.chat.PromptComposer
import com.nekonyan.assistant.core.chat.PromptMessage
import com.nekonyan.assistant.core.chat.SseFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话核心的纯逻辑测试（不依赖 Android，跑得飞快）。
 *
 * 选这三块来钉死，理由都是「错了很难在真机上定位」：
 *   · 配置校验 —— http 地址 / 空 Key 如果不拦，表现只是"发不出去"，用户无从下手；
 *   · SSE 分帧 —— 分帧错了表现为"回复缺字/粘字/半路断"，且不报错；
 *   · 请求体转义 —— 少转义一个引号就是 400，而用户只会看到"请求被拒绝"。
 */
class ChatCoreTest {

    // ==================== 配置 ====================

    @Test
    fun `地址归一化 tolerant 到用户粘贴完整 endpoint`() {
        assertEquals("https://api.deepseek.com", ChatConfig.normalizeBaseUrl("https://api.deepseek.com/"))
        assertEquals(
            "https://api.deepseek.com/v1",
            ChatConfig.normalizeBaseUrl("  https://api.deepseek.com/v1/chat/completions/  ")
        )
        assertEquals(ChatConfig.DEFAULT_BASE_URL, ChatConfig.normalizeBaseUrl(""))
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            ChatConfig.chatCompletionsUrl("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ChatConfig.chatCompletionsUrl("https://api.deepseek.com/v1")
        )
    }

    @Test
    fun `http 地址被拒绝因为 Key 会明文上网`() {
        assertFalse(ChatConfig.isSecureBase("http://api.deepseek.com"))
        assertTrue(ChatConfig.isSecureBase("HTTPS://api.deepseek.com"))
        val problem = ChatConfig(baseUrl = "http://api.deepseek.com", apiKey = "sk-x").blockingProblem()
        assertNotNull(problem)
        assertTrue("提示里应说明 https 要求：$problem", problem!!.contains("https"))
    }

    @Test
    fun `缺 Key 时给出人话原因而不是抛异常`() {
        val problem = ChatConfig(apiKey = "").blockingProblem()
        assertNotNull(problem)
        assertTrue(problem!!.contains("API Key"))
        assertNull(ChatConfig(apiKey = "sk-1234567890").blockingProblem())
    }

    @Test
    fun `半截地址被拦住`() {
        assertFalse(ChatConfig.isValidHost("https://"))
        assertTrue(ChatConfig.isValidHost("https://api.deepseek.com"))
    }

    @Test
    fun `参数越界一律夹取而不是原样发出去`() {
        assertEquals(ChatConfig.MIN_TIMEOUT_SECONDS, ChatConfig.clampTimeout(0))
        assertEquals(ChatConfig.MAX_TIMEOUT_SECONDS, ChatConfig.clampTimeout(9999))
        assertEquals(ChatConfig.MIN_MAX_TOKENS, ChatConfig.clampMaxTokens(1))
        assertEquals(2.0, ChatConfig.clampTemperature(5.0), 0.0)
        assertEquals(ChatConfig.DEFAULT_TEMPERATURE, ChatConfig.clampTemperature(Double.NaN), 0.0)
        assertEquals(ChatConfig.DEFAULT_MODEL, ChatConfig.normalizeModel("   "))
    }

    @Test
    fun `日志描述永不包含 Key 明文`() {
        val line = ChatConfig(apiKey = "sk-abcdef123456").describeForLog()
        assertFalse("日志里不能出现 Key：$line", line.contains("sk-abcdef123456"))
        assertTrue(line.contains("len=15"))
    }

    // ==================== SSE 分帧 ====================

    @Test
    fun `事件以空行结束`() {
        val framer = SseFramer()
        assertNull(framer.pushLine("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", framer.pushLine("")?.data)
    }

    @Test
    fun `多行 data 必须合并成一个事件`() {
        val framer = SseFramer()
        framer.pushLine("data: 第一行")
        framer.pushLine("data: 第二行")
        assertEquals("第一行\n第二行", framer.pushLine("")?.data)
    }

    @Test
    fun `注释与心跳被忽略`() {
        val framer = SseFramer()
        assertNull(framer.pushLine(": keep-alive"))
        assertNull(framer.pushLine("event: message"))
        // 注释与 event 字段都不能被当成数据
        assertNull(framer.pushLine("data: x"))
        assertEquals("x", framer.pushLine("")?.data)
    }

    @Test
    fun `只去掉一个空格`() {
        val framer = SseFramer()
        framer.pushLine("data: 一个空格")
        assertEquals("一个空格", framer.pushLine("")?.data)

        framer.pushLine("data:  两个空格")
        assertEquals(" 两个空格", framer.pushLine("")?.data)
    }

    @Test
    fun `CRLF 的斜杠r 不会混进数据`() {
        val framer = SseFramer()
        framer.pushLine("data: {\"x\":1}\r")
        assertEquals("{\"x\":1}", framer.pushLine("")?.data)
    }

    @Test
    fun `DONE 被识别为流结束`() {
        val framer = SseFramer()
        framer.pushLine("data: [DONE]")
        assertTrue(framer.pushLine("")!!.isDone)
    }

    @Test
    fun `流被掐断时 flush 派发残留帧`() {
        val framer = SseFramer()
        framer.pushLine("data: 尾巴")
        assertTrue(framer.hasPending)
        assertEquals("尾巴", framer.flush()?.data)
        assertNull(framer.flush())
    }

    // ==================== 请求体 ====================

    @Test
    fun `转义覆盖引号 反斜杠 换行 控制字符`() {
        assertEquals("a\\\"b", ChatJson.escape("a\"b"))
        assertEquals("a\\\\b", ChatJson.escape("a\\b"))
        assertEquals("a\\nb", ChatJson.escape("a\nb"))
        assertEquals("a\\tb", ChatJson.escape("a\tb"))
        assertEquals("a\\u0001b", ChatJson.escape("a\u0001b"))
        assertEquals("猫娘", ChatJson.escape("猫娘"))
    }

    @Test
    fun `数字序列化与语言环境无关`() {
        assertEquals("0.7", ChatJson.number(0.7))
        assertEquals("1", ChatJson.number(1.0))
    }

    @Test
    fun `请求体结构正确且括号引号配平`() {
        val messages = listOf(
            PromptMessage.user("你好\"世界\""),
            PromptMessage.assistant("收到")
        )
        val body = ChatJson.chatRequestBody(messages, "deepseek-chat", true, 0.7, 1024)

        assertTrue(body.startsWith("{\"model\":\"deepseek-chat\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"temperature\":0.7"))
        assertTrue("消息内容必须被转义", body.contains("你好\\\"世界\\\""))

        var inString = false
        var escaped = false
        var depth = 0
        var quotes = 0
        for (ch in body) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> { inString = !inString; quotes++ }
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> depth--
            }
        }
        assertEquals("花括号应配平", 0, depth)
        assertFalse("引号应全部闭合", inString)
        assertEquals("引号数应与字段数一致", 28, quotes)
    }

    // ==================== 提示词组装 ====================

    @Test
    fun `系统提示词包含人格 模式 知识库`() {
        val sys = PromptComposer.systemPrompt("小橘", "一只橘猫", "只聊", listOf("用户喜欢 Kotlin", "  "))
        assertTrue(sys.contains("小橘"))
        assertTrue(sys.contains("只聊"))
        assertTrue(sys.contains("用户喜欢 Kotlin"))
        assertFalse("空白知识条目应被丢弃", sys.contains("-  \n"))
    }

    @Test
    fun `没有知识库时不出现知识库段落`() {
        val sys = PromptComposer.systemPrompt(null, null, "陪伴", emptyList())
        assertFalse(sys.contains("知识库"))
        assertTrue("人格缺失时用默认名", sys.contains("猫娘助手"))
    }

    @Test
    fun `知识库超长会被截断`() {
        val sys = PromptComposer.systemPrompt("n", "d", "m", List(50) { "x".repeat(500) })
        assertTrue(
            "实际长度 ${sys.length} 应被限制在 ${PromptComposer.MAX_KNOWLEDGE_CHARS} 附近",
            sys.length < PromptComposer.MAX_KNOWLEDGE_CHARS + 1200
        )
    }

    @Test
    fun `裁剪后首条必须是 user 否则服务端会 400`() {
        val history = listOf(
            PromptMessage.assistant("被裁掉的开头"),
            PromptMessage.user("问题1"),
            PromptMessage.assistant("回答1"),
            PromptMessage.user("问题2")
        )
        val composed = PromptComposer.compose("SYS", history)
        assertEquals("system", composed.first().role)
        assertEquals("user", composed[1].role)
        assertEquals(4, composed.size)
    }

    @Test
    fun `maxHistory 生效且保留最新消息`() {
        val history = listOf(
            PromptMessage.user("问题1"),
            PromptMessage.assistant("回答1"),
            PromptMessage.user("问题2")
        )
        val composed = PromptComposer.compose("SYS", history, maxHistory = 2)
        assertEquals(2, composed.size)
        assertEquals("问题2", composed.last().content)
        assertEquals("user", composed[1].role)
    }

    @Test
    fun `全是 assistant 时只留 system`() {
        val composed = PromptComposer.compose("SYS", listOf(PromptMessage.assistant("只有回复")))
        assertEquals(1, composed.size)
        assertEquals("system", composed.first().role)
    }

    @Test
    fun `空白内容被过滤 空 system 不塞空消息`() {
        assertEquals(1, PromptComposer.compose("SYS", listOf(PromptMessage.user("  "))).size)

        val composed = PromptComposer.compose("", listOf(PromptMessage.user("hi")))
        assertEquals(1, composed.size)
        assertEquals("user", composed.first().role)
    }

    @Test
    fun `会话标题取首行并截断`() {
        assertEquals("帮我写一个", PromptComposer.sessionTitle("帮我写一个\n第二行"))
        assertEquals(
            "一二三四五六七八九十一二三四五六七八…",
            PromptComposer.sessionTitle("一二三四五六七八九十一二三四五六七八九十")
        )
        assertEquals("新会话", PromptComposer.sessionTitle("   "))
        assertEquals("新会话", PromptComposer.sessionTitle(""))
    }
}
