package com.nekonyan.assistant.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 猫娘助手配色系统（需求：橘猫色 / 白猫色 / 黑猫色 / 高对比度）
 *
 * 设计要点（.ds：主题、圆角大、猫爪感；紧急停止：严肃红色，不萌化）：
 *   · 四套主题共用同一组语义槽位，切换主题不影响任何业务代码；
 *   · 高对比度主题走「纯黑白 + 粗描边」，同时满足色盲友好要求
 *     （需求：不只靠颜色，配合图标文字）；
 *   · 紧急停止色单独定义，任何主题下都不做萌化处理。
 */

/** 主题标识（可持久化，见 ThemeConfig） */
enum class NekoThemeId(val label: String, val isDark: Boolean) {
    ORANGE("橘猫", false),
    WHITE("白猫", false),
    BLACK("黑猫", true),
    CONTRAST("高对比度", false);

    companion object {
        val Default = ORANGE          // 需求：默认橘猫色
        fun fromKey(key: String?): NekoThemeId =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}

/**
 * 主题扩展槽位：Material3 的 ColorScheme 里没有「模式色边框 / 猫爪圆角 / 紧急停止」这类语义，
 * 用 CompositionLocal 补充，避免把业务 UI 写死成某个颜色。
 */
data class NekoExtraColors(
    /** 悬浮窗/模式边框色（需求：模式色边框、轻发光） */
    val modeBorder: Color,
    /** 紧急停止（严肃红，不萌化） */
    val emergency: Color,
    val emergencyContent: Color,
    /** 成功提示（喵图标底） */
    val success: Color,
    val warning: Color,
    /** 只读日志的弱化文字 */
    val muted: Color,
    /** 大圆角（猫爪感） */
    val cornerLarge: Int,
    val cornerMedium: Int
)

val LocalNekoExtraColors = staticCompositionLocalOf {
    NekoExtraColors(
        modeBorder = Color(0xFFB07A4A),
        emergency = Color(0xFFC62828),
        emergencyContent = Color.White,
        success = Color(0xFF2E7D32),
        warning = Color(0xFFEF6C00),
        muted = Color(0xFF8A8A8A),
        cornerLarge = 28,
        cornerMedium = 18
    )
}

// ---------------------------------------------------------------
// 四套主题
// ---------------------------------------------------------------

/** 橘猫：#F5A65B 系暖橙（默认主题） */
private val OrangeLight = lightColorScheme(
    primary = Color(0xFFE07B39),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0C2),
    onPrimaryContainer = Color(0xFF4A2400),
    secondary = Color(0xFFB07A4A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E2CE),
    onSecondaryContainer = Color(0xFF3A2A1A),
    tertiary = Color(0xFFE8905A),
    background = Color(0xFFFFF6EC),
    onBackground = Color(0xFF2E241C),
    surface = Color(0xFFFFFBF6),
    onSurface = Color(0xFF2E241C),
    surfaceVariant = Color(0xFFF3E4D6),
    onSurfaceVariant = Color(0xFF5C4A3C),
    outline = Color(0xFFCBB6A4),
    error = Color(0xFFC62828)
)

/** 白猫：冷白 + 银灰 */
private val WhiteLight = lightColorScheme(
    primary = Color(0xFF5B6B7C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9F0),
    onPrimaryContainer = Color(0xFF1B2530),
    secondary = Color(0xFF7C8A99),
    secondaryContainer = Color(0xFFEDF2F6),
    onSecondaryContainer = Color(0xFF232C36),
    tertiary = Color(0xFF8FA3B8),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF1B232B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B232B),
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = Color(0xFF4C5967),
    outline = Color(0xFFB6C2CE),
    error = Color(0xFFB3261E)
)

/** 黑猫：深底浅字 */
private val BlackDark = darkColorScheme(
    primary = Color(0xFFC7A98B),
    onPrimary = Color(0xFF2A211A),
    primaryContainer = Color(0xFF3A2F26),
    onPrimaryContainer = Color(0xFFF0E2D4),
    secondary = Color(0xFF9C8B7A),
    onSecondary = Color(0xFF241D17),
    secondaryContainer = Color(0xFF332A23),
    onSecondaryContainer = Color(0xFFE6D8CA),
    tertiary = Color(0xFFD3B48F),
    background = Color(0xFF1A1512),
    onBackground = Color(0xFFEDE3D8),
    surface = Color(0xFF211B17),
    onSurface = Color(0xFFEDE3D8),
    surfaceVariant = Color(0xFF332B24),
    onSurfaceVariant = Color(0xFFD0C2B4),
    outline = Color(0xFF6B5C4E),
    error = Color(0xFFFF8A80)
)

/** 高对比度：纯黑白 + 高饱和强调色（色盲友好：只作强调，信息另有图标/文字） */
private val ContrastLight = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF000000),
    outline = Color(0xFF000000),
    error = Color(0xFFB00020)
)

/** 每套主题的扩展色（边框/紧急停止/圆角） */
private val extras: Map<NekoThemeId, NekoExtraColors> = mapOf(
    NekoThemeId.ORANGE to NekoExtraColors(
        modeBorder = Color(0xFFE07B39),
        emergency = Color(0xFFC62828), emergencyContent = Color.White,
        success = Color(0xFF2E7D32), warning = Color(0xFFEF6C00),
        muted = Color(0xFF8A7360), cornerLarge = 28, cornerMedium = 18
    ),
    NekoThemeId.WHITE to NekoExtraColors(
        modeBorder = Color(0xFF5B6B7C),
        emergency = Color(0xFFB3261E), emergencyContent = Color.White,
        success = Color(0xFF2E7D32), warning = Color(0xFFE65100),
        muted = Color(0xFF6B7784), cornerLarge = 26, cornerMedium = 16
    ),
    NekoThemeId.BLACK to NekoExtraColors(
        modeBorder = Color(0xFFC7A98B),
        emergency = Color(0xFFFF5252), emergencyContent = Color(0xFF1A1512),
        success = Color(0xFF81C784), warning = Color(0xFFFFB74D),
        muted = Color(0xFF9C8B7A), cornerLarge = 28, cornerMedium = 18
    ),
    NekoThemeId.CONTRAST to NekoExtraColors(
        modeBorder = Color(0xFF000000),
        emergency = Color(0xFFB00020), emergencyContent = Color.White,
        success = Color(0xFF005B0B), warning = Color(0xFF7A3E00),
        muted = Color(0xFF3A3A3A), cornerLarge = 12, cornerMedium = 8
    )
)

fun colorSchemeFor(id: NekoThemeId): ColorScheme = when (id) {
    NekoThemeId.ORANGE -> OrangeLight
    NekoThemeId.WHITE -> WhiteLight
    NekoThemeId.BLACK -> BlackDark
    NekoThemeId.CONTRAST -> ContrastLight
}

fun extraColorsFor(id: NekoThemeId): NekoExtraColors =
    extras[id] ?: extras.getValue(NekoThemeId.Default)

/** 业务代码统一通过这里取扩展色：NekoTheme.extra.emergency */
object NekoTheme {
    val extra: NekoExtraColors
        @Composable @ReadOnlyComposable get() = LocalNekoExtraColors.current

    /** 当前是否处于高对比度主题（用于关闭所有渐变/半透明） */
    val isHighContrast: Boolean
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary == Color.Black
}
