package com.nekonyan.assistant

import com.nekonyan.assistant.core.overlay.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗位置计算的单元测试。
 *
 * 这类边界算错在真机上的表现是"气泡拖到边上就再也点不到"，
 * 只能去设置里关掉重开 —— 而且很难复现，所以用断言锁住。
 */
class OverlayGeometryTest {

    @Test
    fun clamp_keeps_whole_view_on_screen() {
        // 右下超界 → 夹回可见范围
        assertEquals(900 to 1800, OverlayGeometry.clamp(1200, 2000, 200, 300, 1100, 2100))
        // 左上负数 → 夹回 0
        assertEquals(0 to 0, OverlayGeometry.clamp(-50, -80, 200, 300, 1100, 2100))
        // 界内不动
        assertEquals(300 to 400, OverlayGeometry.clamp(300, 400, 200, 300, 1100, 2100))
    }

    @Test
    fun clamp_degrades_gracefully_when_view_larger_than_screen() {
        // 分屏/极端窄屏：视图比屏幕还大时贴 0，而不是算出负数把视图顶出去
        assertEquals(0 to 0, OverlayGeometry.clamp(100, 100, 2000, 3000, 800, 600))
    }

    @Test
    fun snap_picks_nearer_edge() {
        // 1100 宽屏、气泡 200 宽 → 最大 x = 900
        assertEquals(0, OverlayGeometry.snapToEdge(100, 200, 1100))   // 靠左
        assertEquals(900, OverlayGeometry.snapToEdge(700, 200, 1100)) // 靠右
        // 正中间时偏向右侧（>= 半屏判右）
        assertEquals(900, OverlayGeometry.snapToEdge(450, 200, 1100))
    }

    @Test
    fun snap_handles_out_of_range_input() {
        assertEquals(0, OverlayGeometry.snapToEdge(-500, 200, 1100))
        assertEquals(900, OverlayGeometry.snapToEdge(99999, 200, 1100))
    }

    @Test
    fun panel_size_never_overflows_screen() {
        val (w, h) = OverlayGeometry.panelSize(1200, 2000, 1080, 2400)
        assertTrue("宽度不能超屏幕 92%：$w", w <= (1080 * 0.92f).toInt())
        assertTrue("高度不能超屏幕 72%：$h", h <= (2400 * 0.72f).toInt())
        // 小屏上原本就小则不变
        val (w2, h2) = OverlayGeometry.panelSize(300, 400, 1080, 2400)
        assertEquals(300 to 400, w2 to h2)
    }

    @Test
    fun resize_clamps_to_min_so_input_stays_usable() {
        // 往下/往左猛拖：不能缩到没法打字（下限）
        val (w, h) = OverlayGeometry.resize(300, 400, -5000f, -5000f, 1080, 2400)
        assertEquals(OverlayGeometry.MIN_PANEL_W to OverlayGeometry.MIN_PANEL_H, w to h)
    }

    @Test
    fun resize_clamps_to_screen_max() {
        // 往右下猛拖：不能超出屏幕（上限复用 panelSize）
        val (w, h) = OverlayGeometry.resize(300, 400, 5000f, 5000f, 1080, 2400)
        assertTrue("宽度不超 92% 屏宽：$w", w <= (1080 * 0.92f).toInt())
        assertTrue("高度不超 72% 屏高：$h", h <= (2400 * 0.72f).toInt())
    }

    @Test
    fun resize_follows_small_deltas_without_drift() {
        // 起始 600x800，累计 +50/+60 → 650x860（用"起始+累计"而不是逐帧增量，不会漂）
        assertEquals(650 to 860, OverlayGeometry.resize(600, 800, 50f, 60f, 1080, 2400))
    }

    @Test
    fun panel_size_always_positive() {
        val (w, h) = OverlayGeometry.panelSize(0, 0, 1080, 2400)
        assertTrue("尺寸必须为正，否则 WindowManager 直接抛异常", w >= 1 && h >= 1)
    }
}
