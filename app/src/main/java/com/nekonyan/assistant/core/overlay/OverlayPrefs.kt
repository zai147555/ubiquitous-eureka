package com.nekonyan.assistant.core.overlay

import android.content.Context

/**
 * 悬浮窗的小尺寸记忆：面板大小与气泡位置。
 *
 * 存普通 prefs 即可（不是密钥）。为什么值得持久化：
 * 用户把面板拖到合适大小、把气泡挪到顺手的位置，**下次开 App 不该重来一遍**。
 */
class OverlayPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nekonyan_overlay", Context.MODE_PRIVATE)

    fun panelWidth(): Int = prefs.getInt(KEY_W, 0)
    fun panelHeight(): Int = prefs.getInt(KEY_H, 0)
    fun bubbleX(): Int = prefs.getInt(KEY_X, Int.MIN_VALUE)
    fun bubbleY(): Int = prefs.getInt(KEY_Y, Int.MIN_VALUE)

    fun savePanelSize(w: Int, h: Int) =
        prefs.edit().putInt(KEY_W, w).putInt(KEY_H, h).apply()

    fun saveBubblePos(x: Int, y: Int) =
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()

    private companion object {
        const val KEY_W = "panel_w"
        const val KEY_H = "panel_h"
        const val KEY_X = "bubble_x"
        const val KEY_Y = "bubble_y"
    }
}
