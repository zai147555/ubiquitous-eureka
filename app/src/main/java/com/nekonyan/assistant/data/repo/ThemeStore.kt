package com.nekonyan.assistant.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nekonyan.assistant.ui.theme.MotionLevel
import com.nekonyan.assistant.ui.theme.NekoThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "nekonyan_settings")

/**
 * 外观与运行偏好（需求：主题、字体大小、高级动态效果可一键关闭；
 * 支持减少动态效果；低功耗自动降级）
 *
 * 用 DataStore 而不是 SharedPreferences：写入是事务性的、读取是 Flow，
 * 主题切换能立刻反映到界面而无需手动刷新。
 */
data class AppearanceSettings(
    val themeId: NekoThemeId = NekoThemeId.Default,
    val fontScale: Float = 1f,
    val motionLevel: MotionLevel = MotionLevel.FULL,
    /** 需求：低功耗、发热、低电量自动降级 */
    val autoDegrade: Boolean = true,
    /** 需求：视障模式（语音导航、朗读屏幕、大字体） */
    val accessibilityMode: Boolean = false,
    /** 需求：色盲友好（不只靠颜色） */
    val colorBlindFriendly: Boolean = true
)

class ThemeStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_id")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val MOTION = stringPreferencesKey("motion_level")
        val AUTO_DEGRADE = booleanPreferencesKey("auto_degrade")
        val A11Y = booleanPreferencesKey("accessibility_mode")
        val COLOR_BLIND = booleanPreferencesKey("color_blind_friendly")
    }

    val settings: Flow<AppearanceSettings> = context.themeDataStore.data.map { p ->
        AppearanceSettings(
            themeId = NekoThemeId.fromKey(p[Keys.THEME]),
            fontScale = (p[Keys.FONT_SCALE] ?: 1f).coerceIn(0.8f, 1.6f),
            motionLevel = MotionLevel.fromKey(p[Keys.MOTION]),
            autoDegrade = p[Keys.AUTO_DEGRADE] ?: true,
            accessibilityMode = p[Keys.A11Y] ?: false,
            colorBlindFriendly = p[Keys.COLOR_BLIND] ?: true
        )
    }

    suspend fun setTheme(id: NekoThemeId) =
        context.themeDataStore.edit { it[Keys.THEME] = id.name }

    suspend fun setFontScale(scale: Float) =
        context.themeDataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.8f, 1.6f) }

    suspend fun setMotionLevel(level: MotionLevel) =
        context.themeDataStore.edit { it[Keys.MOTION] = level.name }

    suspend fun setAutoDegrade(enabled: Boolean) =
        context.themeDataStore.edit { it[Keys.AUTO_DEGRADE] = enabled }

    suspend fun setAccessibilityMode(enabled: Boolean) =
        context.themeDataStore.edit { it[Keys.A11Y] = enabled }

    suspend fun setColorBlindFriendly(enabled: Boolean) =
        context.themeDataStore.edit { it[Keys.COLOR_BLIND] = enabled }
}
