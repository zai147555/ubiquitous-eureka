package com.nekonyan.assistant.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 高级动态效果（需求：动态模糊、渐变、高斯模糊、淡入淡出、缩放、位移、
 * 弹性、弹簧、共享元素、光晕、呼吸、脉冲、涟漪；且**可一键关闭**、
 * 低功耗自动降级、支持「减少动态效果」）
 *
 * 实现策略：业务代码只问「这一档允许什么」，不直接写死时长，
 * 这样低功耗/无障碍设置能整体降级，而不必改每个界面。
 */
enum class MotionLevel(val label: String) {
    FULL("完整"),
    REDUCED("减少"),
    OFF("关闭");

    companion object {
        fun fromKey(key: String?): MotionLevel =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: FULL
    }
}

/** 弹簧/缓动参数集 */
data class NekoMotion(
    val level: MotionLevel,
    /** 是否允许模糊类效果（动态模糊/高斯模糊/光晕）——最耗 GPU，低端机先砍它 */
    val allowBlur: Boolean,
    /** 是否允许呼吸/脉冲等循环动画 */
    val allowLoop: Boolean,
    /** 是否允许缩放/位移类转场 */
    val allowTransform: Boolean,
    val quickMs: Int,
    val mediumMs: Int,
    val slowMs: Int,
    val easing: Easing
) {
    companion object {
        private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        fun of(level: MotionLevel): NekoMotion = when (level) {
            MotionLevel.FULL -> NekoMotion(
                level = level, allowBlur = true, allowLoop = true, allowTransform = true,
                quickMs = 150, mediumMs = 300, slowMs = 500, easing = Standard
            )
            MotionLevel.REDUCED -> NekoMotion(
                level = level, allowBlur = false, allowLoop = false, allowTransform = true,
                quickMs = 100, mediumMs = 180, slowMs = 240, easing = Standard
            )
            MotionLevel.OFF -> NekoMotion(
                level = level, allowBlur = false, allowLoop = false, allowTransform = false,
                quickMs = 0, mediumMs = 0, slowMs = 0, easing = Standard
            )
        }
    }
}

/**
 * 低功耗/发热降级（需求：温度 >42℃ 只保留核心识别；<20% 电量只提示不操作）
 * 返回建议的动态效果档位 —— 界面层无需理解温度策略。
 */
object MotionPolicy {
    fun suggest(
        userLevel: MotionLevel,
        animatorScaleZero: Boolean,
        thermalCelsius: Float?,
        batteryPercent: Int
    ): MotionLevel {
        // 系统的「移除动画」永远优先
        if (animatorScaleZero) return MotionLevel.OFF
        if (userLevel == MotionLevel.OFF) return MotionLevel.OFF

        val degraded = (thermalCelsius != null && thermalCelsius >= 42f) || batteryPercent < 20
        return when {
            degraded -> MotionLevel.OFF
            userLevel == MotionLevel.REDUCED -> MotionLevel.REDUCED
            else -> MotionLevel.FULL
        }
    }
}
