package com.nekonyan.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 供业务读取的当前动态效果档（不写死时长，便于低功耗整体降级） */
val LocalNekoMotion = staticCompositionLocalOf { NekoMotion.of(MotionLevel.FULL) }

/**
 * 应用主题入口。
 *
 * @param themeId    橘猫 / 白猫 / 黑猫 / 高对比度（需求：默认橘猫色）
 * @param fontScale  字体缩放（需求：字体可调；无障碍要求）
 * @param motionLevel 动态效果档位（需求：高级动态效果 + 可一键关闭）
 * @param systemDark 是否跟随系统深色（黑猫主题本身即深色，不受此影响）
 */
@Composable
fun NekoTheme(
    themeId: NekoThemeId = NekoThemeId.Default,
    fontScale: Float = 1f,
    motionLevel: MotionLevel = MotionLevel.FULL,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val extra = extraColorsFor(themeId)
    val scheme = colorSchemeFor(themeId)

    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(extra.cornerMedium.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(extra.cornerLarge.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape((extra.cornerLarge + 8).dp)
    )

    CompositionLocalProvider(
        LocalNekoExtraColors provides extra,
        LocalNekoMotion provides NekoMotion.of(motionLevel)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(fontScale),
            shapes = shapes,
            content = content
        )
    }
}
