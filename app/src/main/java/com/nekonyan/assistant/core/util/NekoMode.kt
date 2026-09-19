package com.nekonyan.assistant.core.util

/**
 * 运行模式（需求原文：只聊 / 聊天可控 / 伴随 / 自动游戏 / 后台网页 / 视频控制 / 插件扩展）
 *
 * ★ 刻意放在 core/util 而不是 data/db：模式是**跨层概念**（数据库、界面、权限、
 *   日志、任务队列都要用它），把它绑在 Room 实体文件里会导致任何用到模式的
 *   纯逻辑都无法独立编译与测试。实体表里只存 [key] 字符串。
 */
enum class NekoMode(val key: String, val label: String) {
    CHAT_ONLY("chat_only", "只聊"),
    CHAT_CONTROLLED("chat_controlled", "聊天可控"),
    COMPANION("companion", "伴随"),
    AUTO_GAME("auto_game", "自动游戏"),
    BACKGROUND_WEB("background_web", "后台网页"),
    VIDEO_CONTROL("video_control", "视频控制"),
    PLUGIN("plugin", "插件扩展");

    companion object {
        val Default = CHAT_ONLY

        fun fromKey(key: String?): NekoMode =
            entries.firstOrNull { it.key == key } ?: Default

        /** 需求：运行模式（视觉辅助 / 辅助输入 / 切换提示）与上表是两回事，单独定义 */
        val RUN_MODES = listOf("visual", "input", "switch_hint")
    }
}
