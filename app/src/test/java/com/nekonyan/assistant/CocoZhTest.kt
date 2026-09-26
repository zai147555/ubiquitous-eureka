package com.nekonyan.assistant

import com.nekonyan.assistant.core.annot.CocoZh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中文类别名映射的单元测试：错位的表现是"框对了、名字全串位"，最难查。 */
class CocoZhTest {

    @Test
    fun tables_are_aligned_and_complete() {
        assertTrue(CocoZh.isUsable())
        assertEquals(80, CocoZh.EN.size)
        assertEquals(80, CocoZh.ZH.size)
        // 抽查几个关键下标（0=person、15=cat、16=dog、79=toothbrush）
        assertEquals("person", CocoZh.EN[0]); assertEquals("人", CocoZh.ZH[0])
        assertEquals("cat", CocoZh.EN[15]);   assertEquals("猫", CocoZh.ZH[15])
        assertEquals("dog", CocoZh.EN[16]);   assertEquals("狗", CocoZh.ZH[16])
        assertEquals("toothbrush", CocoZh.EN[79]); assertEquals("牙刷", CocoZh.ZH[79])
        // 不允许有重复或空白
        assertEquals(80, CocoZh.EN.toSet().size)
        assertEquals(80, CocoZh.ZH.toSet().size)
        assertTrue(CocoZh.ZH.none { it.isBlank() })
    }

    @Test
    fun localizes_only_when_it_is_exactly_coco80() {
        assertEquals(CocoZh.ZH, CocoZh.localize(CocoZh.EN))
        // 大小写不敏感
        assertEquals(CocoZh.ZH, CocoZh.localize(CocoZh.EN.map { it.uppercase() }))
        // 自定义模型：原样返回（用户起的名字不要动）
        val custom = listOf("敌人", "队友", "物资")
        assertEquals(custom, CocoZh.localize(custom))
        // 80 类但不是 COCO 的顺序 → 也不能翻译（否则名字会整体串位）
        val shuffled = CocoZh.EN.reversed()
        assertEquals(shuffled, CocoZh.localize(shuffled))
    }
}
