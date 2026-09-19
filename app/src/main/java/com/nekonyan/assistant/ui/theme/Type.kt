package com.nekonyan.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体（需求：系统默认字体，可调大小；无障碍要求支持字体大小调节）
 *
 * 字号不在这里写死比例，而是通过 [NekoTheme] 的 fontScale 乘算，
 * 保证「设置页调大字体」能作用于全部文字（含对话框与侧边菜单）。
 */
private val base = Typography()

private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight
)

fun typographyFor(fontScale: Float): Typography {
    val s = fontScale.coerceIn(0.8f, 1.6f)
    if (s == 1f) return base
    return Typography(
        displayLarge = base.displayLarge.scaled(s),
        displayMedium = base.displayMedium.scaled(s),
        displaySmall = base.displaySmall.scaled(s),
        headlineLarge = base.headlineLarge.scaled(s),
        headlineMedium = base.headlineMedium.scaled(s),
        headlineSmall = base.headlineSmall.scaled(s),
        titleLarge = base.titleLarge.scaled(s),
        titleMedium = base.titleMedium.scaled(s),
        titleSmall = base.titleSmall.scaled(s),
        bodyLarge = base.bodyLarge.scaled(s),
        bodyMedium = base.bodyMedium.scaled(s),
        bodySmall = base.bodySmall.scaled(s),
        labelLarge = base.labelLarge.scaled(s),
        labelMedium = base.labelMedium.scaled(s),
        labelSmall = base.labelSmall.scaled(s)
    )
}

/** 紧急停止按钮专用的加粗样式：任何主题下都保持"严肃、醒目"（不做萌化） */
val EmergencyTextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    letterSpacing = 1.sp
)
