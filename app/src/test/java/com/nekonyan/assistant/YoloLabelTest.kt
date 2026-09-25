package com.nekonyan.assistant

import com.nekonyan.assistant.core.annot.YoloLabel
import com.nekonyan.assistant.core.annot.YoloLabel.Box
import com.nekonyan.assistant.core.annot.YoloLabel.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标注核心逻辑的单元测试 —— 这一层错了在手机上很难看出来：
 * 框整体偏移、拖出画面后标签被训练器丢弃、0 宽高的框……
 */
class YoloLabelTest {

    @Test
    fun parse_round_trips_and_is_tolerant() {
        val text = """
            0 0.500000 0.500000 0.200000 0.100000
            1 0.25 0.75 0.5 0.5

            坏行会被跳过
            2 0.1 0.2 0.3
            3 a b c d
            4 0.1 0.2 0.3 0.4
        """.trimIndent()
        val boxes = YoloLabel.parse(text)
        assertEquals(3, boxes.size)                     // 只留合法行
        assertEquals(0, boxes[0].cls)
        assertEquals(4, boxes[2].cls)
        // 再序列化 → 再解析，必须稳定（否则每次保存都在漂）
        val again = YoloLabel.parse(YoloLabel.serialize(boxes))
        assertEquals(boxes.size, again.size)
        for (i in boxes.indices) {
            assertEquals(boxes[i].cls, again[i].cls)
            assertEquals(boxes[i].cx, again[i].cx, 1e-5f)
            assertEquals(boxes[i].w, again[i].w, 1e-5f)
        }
    }

    @Test
    fun normalize_clamps_into_unit_square_and_keeps_min_side() {
        // 拖到画面外：中心点与边长都被夹回，绝不产生负数或 >1
        val b = YoloLabel.normalize(Box(0, -0.5f, 1.5f, 3f, 0.001f))
        assertTrue("cx 必须在 (0,1)", b.cx in 0f..1f)
        assertTrue("cy 必须在 (0,1)", b.cy in 0f..1f)
        assertTrue("宽不能超 1", b.w <= 1f)
        assertTrue("高不能小于下限", b.h >= YoloLabel.MIN_SIDE)
        // 夹取后仍不能越界：左边界 >= 0 且右边界 <= 1
        assertTrue(b.cx - b.w / 2f >= -1e-6f)
        assertTrue(b.cx + b.w / 2f <= 1f + 1e-6f)
    }

    @Test
    fun pixel_conversion_is_consistent_both_ways() {
        val imgW = 1080; val imgH = 2400
        val r = PxRect(108f, 240f, 540f, 720f)
        val box = YoloLabel.fromRect(7, r, imgW, imgH)
        assertEquals(7, box.cls)
        val back = YoloLabel.toRect(box, imgW, imgH)
        assertEquals(r.left, back.left, 0.5f)
        assertEquals(r.top, back.top, 0.5f)
        assertEquals(r.right, back.right, 0.5f)
        assertEquals(r.bottom, back.bottom, 0.5f)
    }

    @Test
    fun from_rect_handles_reversed_drag() {
        // 用户从右下往左上拖：宽高必须取绝对值，而不是负值
        val box = YoloLabel.fromRect(0, PxRect(540f, 720f, 108f, 240f), 1080, 2400)
        assertTrue(box.w > 0f && box.h > 0f)
        assertEquals(0.3f, box.cx, 0.01f)   // (108+540)/2/1080
        assertEquals(0.2f, box.cy, 0.01f)   // (240+720)/2/2400
    }

    @Test
    fun hit_test_prefers_topmost() {
        val small = Box(0, 0.5f, 0.5f, 0.2f, 0.2f)
        val big = Box(1, 0.5f, 0.5f, 0.8f, 0.8f)     // 后画的，压在 small 上
        val boxes = listOf(big, small)
        assertEquals(1, YoloLabel.hitTest(boxes, 540f, 1200f, 1080, 2400))
        // 空白处不命中
        assertNull(YoloLabel.hitTest(boxes, 5f, 5f, 1080, 2400))
        // 空列表安全
        assertNull(YoloLabel.hitTest(emptyList(), 540f, 1200f, 1080, 2400))
    }

    @Test
    fun move_and_resize_stay_inside() {
        val b = Box(0, 0.5f, 0.5f, 0.2f, 0.2f)
        val moved = YoloLabel.move(b, 10f, -10f)          // 猛拖
        assertTrue(moved.cx <= 1f && moved.cy >= 0f)
        val resized = YoloLabel.resizeFromTopLeft(b, -10f, -10f)
        assertTrue("缩到最小也不能为 0", resized.w >= YoloLabel.MIN_SIDE && resized.h >= YoloLabel.MIN_SIDE)
        // 小幅放大（不触发夹取）→ 左上角必须原地不动
        val grown = YoloLabel.resizeFromTopLeft(b, 0.1f, 0.1f)
        assertEquals(b.cx - b.w / 2f, grown.cx - grown.w / 2f, 1e-5f)
        assertEquals(b.cy - b.h / 2f, grown.cy - grown.h / 2f, 1e-5f)
        // 极端放大（远超画面）→ 夹回框内：宽高不超 1、且不能越界
        //   注意此时左上角**必然**会移动 —— 这是我第一版断言写错的地方（以为角点永远不动）
        val huge = YoloLabel.resizeFromTopLeft(b, 10f, 10f)
        assertTrue("宽不能超 1", huge.w <= 1f)
        assertTrue("高不能超 1", huge.h <= 1f)
        assertTrue("左边不能出画", huge.cx - huge.w / 2f >= -1e-6f)
        assertTrue("右边不能出画", huge.cx + huge.w / 2f <= 1f + 1e-6f)
    }

    @Test
    fun empty_inputs_are_safe() {
        assertTrue(YoloLabel.parse("").isEmpty())
        assertEquals("", YoloLabel.serialize(emptyList()))
        assertNotNull(YoloLabel.fromRect(0, PxRect(0f, 0f, 0f, 0f), 0, 0))
    }
}
