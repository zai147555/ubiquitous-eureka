package com.nekonyan.assistant

import com.nekonyan.assistant.core.voice.EdgeTtsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微软官方 Edge TTS 客户端的**纯函数**单元测试（不联网、不依赖 Android）。
 *
 * 为什么值得测：这几个值一旦算错，表现是"真机上一直 403 / 服务端不返回音频"，
 * 而端上又看不到中间量 —— 只能在真机反复试。所以把它们钉死在测试里。
 *
 * 固定值来源：与官方开源实现 rany2/edge-tts 的 drm.py **逐位对拍**通过
 * （Python 版算出的 Sec-MS-GEC 与这里的期望值完全一致）。
 */
class EdgeTtsTest {

    @Test
    fun secMsGec_matches_official_implementation() {
        // 三个固定时间点，期望值由官方 Python 实现的算法算出并逐位核对
        assertEquals(
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            EdgeTtsClient.secMsGec(0.0, 1_700_000_000_000L)
        )
        assertEquals(
            "A340B983D4C9DC87CC2CDA9C0BAFDA79BF92F191835475413B1D082A1124A424",
            EdgeTtsClient.secMsGec(0.0, 1_756_000_000_000L)
        )
        assertEquals(
            "3E89AD0C90D5D3645C2BC144E74F8125B061B3AD0581F65A550A7962A2561197",
            EdgeTtsClient.secMsGec(0.0, 1_752_000_123_456L)
        )
    }

    @Test
    fun secMsGec_is_stable_within_five_minute_window_and_changes_after() {
        // 同一 5 分钟窗口内必须完全一致（服务端就是这么取整的），跨窗口必须变
        val base = 1_700_000_000_000L
        val a = EdgeTtsClient.secMsGec(0.0, base)
        val b = EdgeTtsClient.secMsGec(0.0, base + 60_000L)
        assertEquals(a, b)
        // 时钟偏差会改变结果（这正是 403 重试依赖的性质）
        assertNotNull(EdgeTtsClient.secMsGec(1800.0, base))
    }

    @Test
    fun secMsGec_is_uppercase_hex_of_sha256() {
        val v = EdgeTtsClient.secMsGec(0.0, 1_700_000_000_000L)
        assertEquals(64, v.length)
        assertTrue("必须是大写十六进制", v.all { it in "0123456789ABCDEF" })
        assertEquals(v, v.uppercase())
    }

    @Test
    fun jsDate_matches_edge_format() {
        // 注意格式里那个字面量 (Coordinated Universal Time) 是官方实现的一部分
        assertEquals(
            "Tue Nov 14 2023 22:13:20 GMT+0000 (Coordinated Universal Time)",
            EdgeTtsClient.jsDate(1_700_000_000_000L)
        )
    }

    @Test
    fun speech_config_declares_mp3_output_and_ends_with_crlf() {
        val msg = EdgeTtsClient.speechConfigMessage(1_700_000_000_000L)
        assertTrue(msg.contains("Path:speech.config"))
        assertTrue(msg.contains("audio-24khz-48kbitrate-mono-mp3"))
        assertTrue("官方实现末尾带 CRLF", msg.endsWith("}}\r\n"))
        // 头与体之间必须是空行，否则服务端当正文解析
        assertTrue(msg.contains("\r\n\r\n"))
    }

    @Test
    fun ssml_message_has_required_headers_and_escaped_text() {
        val msg = EdgeTtsClient.ssmlMessage(
            "REQ", "a<b>&c", "zh-CN-XiaoxiaoNeural", "+0%", "+0Hz", 1_700_000_000_000L
        )
        assertTrue(msg.startsWith("X-RequestId:REQ\r\n"))
        assertTrue(msg.contains("Content-Type:application/ssml+xml"))
        // SSML 那处时间戳末尾要再补一个 Z（官方实现如此，不是笔误）
        assertTrue(msg.contains("(Coordinated Universal Time)Z\r\n"))
        assertTrue(msg.contains("Path:ssml\r\n\r\n"))
        // 文本必须转义，否则 & 会让 SSML 解析失败
        assertTrue(msg.contains("a&lt;b&gt;&amp;c"))
        assertFalse(msg.contains("a<b>&c"))
    }

    @Test
    fun xml_escape_and_incompatible_chars() {
        assertEquals("a&amp;b&lt;c&gt;d", EdgeTtsClient.escapeXml("a&b<c>d"))
        // 0-8 / 11-12 / 14-31 会被服务端拒绝，替换成空格
        assertEquals("a b c", EdgeTtsClient.removeIncompatible("a\u0007b\u000Bc"))
        assertEquals("保留换行与制表", "a\nb\tc", EdgeTtsClient.removeIncompatible("a\nb\tc"))
    }

    @Test
    fun chunking_respects_byte_limit_and_keeps_content() {
        val long = "第一段。\n".repeat(700) + "😀结尾"
        val chunks = EdgeTtsClient.chunkByBytes(long, 4096)
        assertTrue("应当切成多块", chunks.size > 1)
        assertTrue(
            "每块都不超过上限",
            chunks.all { it.toByteArray(Charsets.UTF_8).size <= 4096 }
        )
        // 去掉空白后内容不能丢（切分处会调整空白）
        fun strip(s: String) = s.filterNot { it.isWhitespace() }
        assertEquals(strip(long), strip(chunks.joinToString("")))
        assertTrue("emoji 不能被截断", chunks.last().contains("😀"))
    }

    @Test
    fun chunking_edge_cases() {
        assertEquals(1, EdgeTtsClient.chunkByBytes("你好").size)
        assertTrue("全空白没有可合成内容", EdgeTtsClient.chunkByBytes("   ").isEmpty())
        assertTrue(EdgeTtsClient.chunkByBytes("").isEmpty())
    }

    @Test
    fun header_parsing_reads_path_and_content_type() {
        // 实测的二进制帧头部文本（末尾带 CRLF）
        val header = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nX-StreamId:1DBD\r\nPath:audio\r\n"
        assertEquals("audio", EdgeTtsClient.headerValue(header, "Path"))
        assertEquals("audio/mpeg", EdgeTtsClient.headerValue(header, "Content-Type"))
        assertNull(EdgeTtsClient.headerValue(header, "X-Missing"))
        // 头部之后再出现同名文本不能被误读（只扫头部区）
        val withBody = "Path:turn.end\r\n\r\nPath:audio"
        assertEquals("turn.end", EdgeTtsClient.headerValue(withBody, "Path"))
    }

    @Test
    fun http_date_parsing_for_clock_skew_retry() {
        val sec = EdgeTtsClient.parseHttpDate("Thu, 24 Sep 2026 22:09:40 GMT")
        assertNotNull("403 重试依赖这个解析", sec)
        assertEquals(1_790_287_780.0, sec!!, 0.5)   // 2026-09-24T22:09:40Z
        assertNull(EdgeTtsClient.parseHttpDate("不是日期"))
    }

    @Test
    fun fallback_voice_list_is_usable() {
        assertTrue(EdgeTtsClient.FALLBACK_VOICES.size >= 5)
        assertTrue(
            "兜底列表里必须有默认音色，否则保存后会指向不存在的音色",
            EdgeTtsClient.FALLBACK_VOICES.any { it.shortName == EdgeTtsClient.DEFAULT_VOICE }
        )
        assertTrue(EdgeTtsClient.FALLBACK_VOICES.all { it.shortName.isNotBlank() })
    }

    @Test
    fun constants_match_official_client_identity() {
        assertEquals("6A5AA1D4EAFF4E9FB37E23D68491D6F4", EdgeTtsClient.TRUSTED_CLIENT_TOKEN)
        assertEquals("1-143.0.3650.75", EdgeTtsClient.SEC_MS_GEC_VERSION)
        assertTrue(EdgeTtsClient.WSS_URL.startsWith("wss://speech.platform.bing.com/"))
        assertTrue(EdgeTtsClient.WSS_URL.contains("TrustedClientToken="))
        // OkHttp 的 WebSocket 要求 ws/wss scheme，写成 https 会直接抛异常
        assertTrue(EdgeTtsClient.WSS_URL.startsWith("wss://"))
    }
}
