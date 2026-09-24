package com.nekonyan.assistant

import com.nekonyan.assistant.core.voice.AutoSpeakDecision
import com.nekonyan.assistant.core.voice.AutoSpeakPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动朗读」判定的单元测试 —— 这三条规则坏掉时的表现都很像 bug：
 * 一进 App 念历史、开了开关补念旧消息、同一条念两遍。
 */
class AutoSpeakPolicyTest {

    @Test
    fun first_emission_only_establishes_baseline() {
        // 首次（即便开关是开的）：只记基准，不念历史
        val d = AutoSpeakPolicy.decide(
            baselineReady = false, seenId = null, newId = "m1", newText = "上次的回复", enabled = true
        )
        assertEquals(AutoSpeakDecision.Remember("m1"), d)
    }

    @Test
    fun new_message_speaks_when_enabled() {
        val d = AutoSpeakPolicy.decide(
            baselineReady = true, seenId = "m1", newId = "m2", newText = "新回复", enabled = true
        )
        assertEquals(AutoSpeakDecision.Speak("m2", "新回复"), d)
    }

    @Test
    fun same_message_is_never_spoken_twice() {
        // Room 的 Flow 会重复发同一份列表
        val d = AutoSpeakPolicy.decide(
            baselineReady = true, seenId = "m2", newId = "m2", newText = "新回复", enabled = true
        )
        assertEquals(AutoSpeakDecision.Ignore, d)
    }

    @Test
    fun disabled_advances_baseline_so_it_is_not_read_later() {
        // 关着开关时收到 m3：要记为已处理，用户之后打开开关不能补念 m3
        val d = AutoSpeakPolicy.decide(
            baselineReady = true, seenId = "m2", newId = "m3", newText = "关着时来的回复", enabled = false
        )
        assertEquals(AutoSpeakDecision.Remember("m3"), d)

        // 紧接着来 m4 且开关已打开 → 只念 m4
        val d2 = AutoSpeakPolicy.decide(
            baselineReady = true, seenId = "m3", newId = "m4", newText = "打开后的回复", enabled = true
        )
        assertEquals(AutoSpeakDecision.Speak("m4", "打开后的回复"), d2)
    }

    @Test
    fun blank_text_is_remembered_not_spoken() {
        val d = AutoSpeakPolicy.decide(
            baselineReady = true, seenId = "m1", newId = "m2", newText = "   ", enabled = true
        )
        assertEquals(AutoSpeakDecision.Remember("m2"), d)
    }

    @Test
    fun sequence_speaks_each_new_message_exactly_once() {
        // 模拟"基准已建立（历史里已有 old），随后连续三条新回复"：应各念一次
        var seen: String? = "old"
        var ready = true
        val spoken = mutableListOf<String>()
        for ((id, text) in listOf("a" to "一", "b" to "二", "c" to "三")) {
            when (val d = AutoSpeakPolicy.decide(ready, seen, id, text, enabled = true)) {
                is AutoSpeakDecision.Speak -> { spoken += d.text; seen = d.id }
                is AutoSpeakDecision.Remember -> seen = d.id
                AutoSpeakDecision.Ignore -> Unit
            }
            ready = true
        }
        assertEquals(listOf("一", "二", "三"), spoken)
        assertEquals("c", seen)
    }
}
