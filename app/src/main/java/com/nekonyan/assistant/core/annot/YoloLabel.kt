package com.nekonyan.assistant.core.annot

/**
 * YOLO 标签的解析/生成与坐标换算（**纯逻辑，不依赖 Android**，便于单元测试）。
 *
 * 为什么单独抽出来：标注界面里最容易错、又最难在真机上发现的，就是这一层 ——
 *   · 归一化坐标 ↔ 像素坐标来回换错一个方向，框会整体跑到右下角；
 *   · 忘了 clamp，拖出画面后标签里出现负数或 >1 的值，训练时被 ultralytics 直接丢弃；
 *   · 框宽高算成 0 或负数，同样被丢弃，而且用户只看到"标了半天没效果"。
 * 这些都能用断言锁住，不该靠肉眼在手机上试。
 *
 * 坐标约定（与 YOLO 一致）：归一化、中心点 + 宽高，**值域 0..1**。
 */
object YoloLabel {

    /** 归一化框（YOLO 格式） */
    data class Box(val cls: Int, val cx: Float, val cy: Float, val w: Float, val h: Float)

    /** 像素矩形（左上右下），仅用于绘制与手势 */
    data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 允许的最小边长（归一化）：太小会被训练器当噪声丢掉 */
    const val MIN_SIDE = 0.004f

    /** 解析标签文本：宽容跳过格式异常行（宁可少一个框，也不要整张图读失败） */
    fun parse(text: String): List<Box> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val p = line.split(' ', '\t').filter { it.isNotBlank() }
            if (p.size != 5) return@mapNotNull null
            val cls = p[0].toIntOrNull() ?: return@mapNotNull null
            val v = p.drop(1).map { it.toFloatOrNull() ?: return@mapNotNull null }
            normalize(Box(cls, v[0], v[1], v[2], v[3]))
        }
        .toList()

    /** 生成标签文本：固定 6 位小数（与常见工具一致，避免无意义的 diff） */
    fun serialize(boxes: List<Box>): String =
        boxes.joinToString("\n") { b ->
            val n = normalize(b)
            "%d %.6f %.6f %.6f %.6f".format(n.cls, n.cx, n.cy, n.w, n.h)
        }

    /** 把框夹回 0..1，并保证边长不小于 [MIN_SIDE]、不越界（越界会破坏中心点语义） */
    fun normalize(b: Box): Box {
        var w = b.w.coerceIn(MIN_SIDE, 1f)
        var h = b.h.coerceIn(MIN_SIDE, 1f)
        var cx = b.cx.coerceIn(w / 2f, 1f - w / 2f)
        var cy = b.cy.coerceIn(h / 2f, 1f - h / 2f)
        return Box(b.cls, cx, cy, w, h)
    }

    /** 归一化 → 像素矩形 */
    fun toRect(b: Box, imgW: Int, imgH: Int): PxRect {
        val n = normalize(b)
        return PxRect(
            left = (n.cx - n.w / 2f) * imgW,
            top = (n.cy - n.h / 2f) * imgH,
            right = (n.cx + n.w / 2f) * imgW,
            bottom = (n.cy + n.h / 2f) * imgH
        )
    }

    /** 像素矩形 → 归一化框（用户拖出来的框走这条） */
    fun fromRect(cls: Int, r: PxRect, imgW: Int, imgH: Int): Box {
        if (imgW <= 0 || imgH <= 0) return Box(cls, 0.5f, 0.5f, MIN_SIDE, MIN_SIDE)
        val l = minOf(r.left, r.right); val rr = maxOf(r.left, r.right)
        val t = minOf(r.top, r.bottom); val b = maxOf(r.top, r.bottom)
        return normalize(
            Box(
                cls = cls,
                cx = ((l + rr) / 2f) / imgW,
                cy = ((t + b) / 2f) / imgH,
                w = (rr - l) / imgW,
                h = (b - t) / imgH
            )
        )
    }

    /** 命中测试：返回**最上层**（列表最后一个）包含该点的框下标；都不命中返回 null */
    fun hitTest(boxes: List<Box>, xPx: Float, yPx: Float, imgW: Int, imgH: Int): Int? {
        for (i in boxes.indices.reversed()) {
            val r = toRect(boxes[i], imgW, imgH)
            if (xPx >= r.left && xPx <= r.right && yPx >= r.top && yPx <= r.bottom) return i
        }
        return null
    }

    /** 平移（按归一化增量），夹回画面内 */
    fun move(b: Box, dxNorm: Float, dyNorm: Float): Box = normalize(b.copy(cx = b.cx + dxNorm, cy = b.cy + dyNorm))

    /**
     * 缩放手势：固定左上角，拖右下角（标注里最常用的一种）。
     * 边长不足 [MIN_SIDE] 时由 [normalize] 兜住，不会产生 0 宽高的框。
     */
    fun resizeFromTopLeft(b: Box, dxNorm: Float, dyNorm: Float): Box {
        val left = b.cx - b.w / 2f
        val top = b.cy - b.h / 2f
        val newW = (b.w + dxNorm).coerceAtLeast(MIN_SIDE)
        val newH = (b.h + dyNorm).coerceAtLeast(MIN_SIDE)
        return normalize(Box(b.cls, left + newW / 2f, top + newH / 2f, newW, newH))
    }

    /** 换类别（其余不动） */
    fun withClass(b: Box, cls: Int): Box = normalize(b.copy(cls = cls))
}
