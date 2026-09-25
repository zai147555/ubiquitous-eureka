package com.nekonyan.assistant.core.overlay

/**
 * 悬浮气泡的位置与尺寸计算（纯函数，便于测试）。
 *
 * 为什么单独抽出来测：气泡被拖出屏幕就**再也点不到了** —— 用户只能去设置里关掉再开，
 * 而这类"边界算错"在真机上极难复现。所以把夹取/吸附做成可断言的纯逻辑。
 */
object OverlayGeometry {

    /**
     * 把坐标夹到"整个视图都在屏幕内"的范围。
     * 屏幕比视图还小时（极端窄屏 / 分屏）退化为贴左上角，而不是算出负数把视图顶出去。
     */
    fun clamp(x: Int, y: Int, viewW: Int, viewH: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /** 松手后吸附到较近的水平边缘（左/右）；纵向不动，避免气泡自己跳走 */
    fun snapToEdge(x: Int, viewW: Int, screenW: Int): Int {
        val maxX = (screenW - viewW).coerceAtLeast(0)
        val clamped = x.coerceIn(0, maxX)
        val center = clamped + viewW / 2
        return if (center < screenW / 2) 0 else maxX
    }

    /** 面板可缩到的最小尺寸（再小就没法打字了） */
    const val MIN_PANEL_W = 220
    const val MIN_PANEL_H = 200

    /**
     * 拖动右下角把手时的尺寸计算。
     *
     * 下限用 [MIN_PANEL_W]/[MIN_PANEL_H]（否则输入框会被压没、没法再拖回来），
     * 上限复用 [panelSize]（不能超出屏幕）。用"起始尺寸 + 累计位移"而不是逐帧增量，
     * 这样手指抖动不会越积越偏。
     */
    fun resize(startW: Int, startH: Int, totalDx: Float, totalDy: Float, screenW: Int, screenH: Int): Pair<Int, Int> {
        val w = (startW + totalDx).toInt().coerceAtLeast(MIN_PANEL_W)
        val h = (startH + totalDy).toInt().coerceAtLeast(MIN_PANEL_H)
        return panelSize(w, h, screenW, screenH)
    }

    /**
     * 展开面板的尺寸：不超过屏幕的 92% 宽 / 72% 高。
     * 横屏与小平板上不夹取会直接溢出到屏幕外，输入框被顶没。
     */
    fun panelSize(desiredW: Int, desiredH: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val maxW = (screenW * 0.92f).toInt()
        val maxH = (screenH * 0.72f).toInt()
        return desiredW.coerceIn(1, maxW.coerceAtLeast(1)) to
            desiredH.coerceIn(1, maxH.coerceAtLeast(1))
    }
}
