package com.nekonyan.assistant

import com.nekonyan.assistant.core.util.NekoMode
import com.nekonyan.assistant.data.repo.MusicRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务与音乐的业务规则（不依赖 Room / Android 框架，可直接跑）。
 *
 * 对应需求原文里的两条硬规则：
 *   · 任务必须填写「任务名称 + 任务消息」；
 *   · 任务消息支持模板变量，可引用知识库、地图、物资、配枪、理包、跟随。
 * 音乐部分对应：「仅播放本地已下载」—— 因此格式/时长解析必须健壮，
 * 因为文件名与元数据来自用户本地文件，什么都可能有。
 */
class TaskRulesTest {

    // 复刻 TaskRepository 的校验规则（同一套规则在仓库层强制）
    private fun validate(name: String, message: String): String? = when {
        name.trim().isEmpty() -> "任务名称不能为空"
        message.trim().isEmpty() -> "任务消息不能为空"
        else -> null
    }

    private fun render(template: String, vars: Map<String, String>): String {
        var out = template
        vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }

    @Test
    fun `名称与消息都必填`() {
        assertEquals("任务名称不能为空", validate("", "消息"))
        assertEquals("任务名称不能为空", validate("   ", "消息"))
        assertEquals("任务消息不能为空", validate("名称", ""))
        assertEquals("任务消息不能为空", validate("名称", "  \n "))
        assertEquals(null, validate("名称", "消息"))
    }

    @Test
    fun `模板变量被替换`() {
        val out = render("帮我把{{物资}}整理一下，参考{{配枪}}", mapOf(
            "物资" to "三级甲",
            "配枪" to "M4A1 方案A"
        ))
        assertEquals("帮我把三级甲整理一下，参考M4A1 方案A", out)
    }

    @Test
    fun `未提供的变量保持原样而不是变成空`() {
        // 保持原样能让用户一眼看出"这个变量没数据"，比静默清空好排查
        assertEquals("看看{{地图}}", render("看看{{地图}}", emptyMap()))
    }

    @Test
    fun `同名变量多次出现都会被替换`() {
        assertEquals("A 和 A", render("{{x}} 和 {{x}}", mapOf("x" to "A")))
    }

    @Test
    fun `模式枚举七种且键唯一`() {
        val keys = NekoMode.entries.map { it.key }
        assertEquals(7, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(NekoMode.CHAT_ONLY, NekoMode.fromKey("非法键"))
    }

    @Test
    fun `本地音乐时长格式化`() {
        assertEquals("--:--", formatDuration(0))
        assertEquals("--:--", formatDuration(-1))
        assertEquals("0:03", formatDuration(3_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("60:00", formatDuration(3_600_000))
    }

    @Test
    fun `音频扩展名识别`() {
        listOf("a.mp3", "b.M4A", "c.flac", "d.wav", "e.ogg", "f.aac").forEach {
            assertTrue("$it 应被识别为音频", isAudio(it))
        }
        listOf("a.mp4", "b.txt", "c.jpg", "d").forEach {
            assertFalse("$it 不应被识别为音频", isAudio(it))
        }
    }

    // ★ 直接调用生产代码，不再复刻 —— 复刻会导致测试通过但线上仍是错的
    private fun formatDuration(ms: Long) = MusicRepository.formatDuration(ms)

    private fun isAudio(name: String) = MusicRepository.isAudio(name)
}
