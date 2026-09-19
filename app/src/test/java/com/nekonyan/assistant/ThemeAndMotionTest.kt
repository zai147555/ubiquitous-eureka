package com.nekonyan.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.core.util.NekoMode
import androidx.compose.ui.graphics.Color
import com.nekonyan.assistant.ui.theme.MotionLevel
import com.nekonyan.assistant.ui.theme.MotionPolicy
import com.nekonyan.assistant.ui.theme.NekoMotion
import com.nekonyan.assistant.ui.theme.NekoThemeId
import com.nekonyan.assistant.ui.theme.colorSchemeFor
import com.nekonyan.assistant.ui.theme.extraColorsFor
import com.nekonyan.assistant.ui.theme.typographyFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主题与动态效果（需求：橘猫/白猫/黑猫/高对比度；默认橘猫色；
 * 高级动态效果可一键关闭；低功耗、发热、低电量自动降级；
 * 支持减少动态效果 —— 系统动画缩放为 0 时必须完全关闭动画）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ThemeAndMotionTest {

    @Test
    fun `默认主题是橘猫`() {
        assertEquals(NekoThemeId.ORANGE, NekoThemeId.Default)
    }

    @Test
    fun `四套主题都有配色方案`() {
        NekoThemeId.entries.forEach { id ->
            val scheme = colorSchemeFor(id)
            assertNotEquals("$id 的主色不应为默认值", 0uL, scheme.primary.value)
            val extra = extraColorsFor(id)
            assertTrue("$id 必须有紧急停止色", extra.emergency.value.toLong() != 0L)
        }
    }

    @Test
    fun `紧急停止色在任何主题下都不是萌化色`() {
        // 需求：紧急停止严肃红色，不萌化 —— 检查红色分量显著高于蓝绿
        NekoThemeId.entries.forEach { id ->
            val c = extraColorsFor(id).emergency
            assertTrue(
                "$id 的紧急停止色应偏红（实际 r=${c.red} g=${c.green} b=${c.blue}）",
                c.red > c.green && c.red > c.blue
            )
        }
    }

    @Test
    fun `黑猫主题是深色背景`() {
        val bg = colorSchemeFor(NekoThemeId.BLACK).background
        assertTrue("黑猫主题背景应偏暗", bg.red < 0.35f && bg.green < 0.35f && bg.blue < 0.35f)
        assertTrue(NekoThemeId.BLACK.isDark)
    }

    @Test
    fun `高对比度主题是纯黑白`() {
        val s = colorSchemeFor(NekoThemeId.CONTRAST)
        // ★ 不要比较 Color.value（ULong）：Color.Black 走 ColorSpace 定义，
        //   而 Color(0xFF000000) 走 32 位打包构造，两者内部表示不同但视觉等价。
        //   断言应该针对「视觉上的颜色分量」，并留出浮点容差。
        val eps = 0.002f
        assertEquals("背景应接近纯黑(R)", 0f, s.background.red, eps)
        assertEquals("背景应接近纯黑(G)", 0f, s.background.green, eps)
        assertEquals("背景应接近纯黑(B)", 0f, s.background.blue, eps)
        assertEquals("前景应接近纯白(R)", 1f, s.onBackground.red, eps)
        assertEquals("前景应接近纯白(G)", 1f, s.onBackground.green, eps)
        assertEquals("前景应接近纯白(B)", 1f, s.onBackground.blue, eps)
    }

    @Test
    fun `未知主题键回退到默认`() {
        assertEquals(NekoThemeId.Default, NekoThemeId.fromKey(null))
        assertEquals(NekoThemeId.Default, NekoThemeId.fromKey("不存在的主题"))
        assertEquals(NekoThemeId.BLACK, NekoThemeId.fromKey("black"))
    }

    @Test
    fun `字体缩放会作用于字号且被限幅`() {
        val normal = typographyFor(1f).bodyLarge.fontSize.value
        val big = typographyFor(1.5f).bodyLarge.fontSize.value
        assertTrue("放大后字号应变大", big > normal)

        // 超出范围会被夹到 1.6 以内，防止布局被撑坏
        val clamped = typographyFor(9f).bodyLarge.fontSize.value
        val maxAllowed = typographyFor(1.6f).bodyLarge.fontSize.value
        assertEquals(maxAllowed, clamped, 0.01f)
    }

    @Test
    fun `关闭动态效果时禁用模糊与循环动画`() {
        val m = NekoMotion.of(MotionLevel.OFF)
        assertFalse(m.allowBlur)
        assertFalse(m.allowLoop)
        assertFalse(m.allowTransform)
        assertEquals(0, m.quickMs)
    }

    @Test
    fun `系统移除动画时无论用户怎么选都关闭`() {
        // 需求：支持减少动态效果
        MotionLevel.entries.forEach { user ->
            assertEquals(
                "系统动画缩放为 0 时必须完全关闭",
                MotionLevel.OFF,
                MotionPolicy.suggest(user, animatorScaleZero = true, thermalCelsius = 20f, batteryPercent = 100)
            )
        }
    }

    @Test
    fun `发热超过 42 度自动降级为关闭`() {
        assertEquals(
            MotionLevel.OFF,
            MotionPolicy.suggest(MotionLevel.FULL, false, thermalCelsius = 43f, batteryPercent = 100)
        )
    }

    @Test
    fun `低电量时自动降级为关闭`() {
        assertEquals(
            MotionLevel.OFF,
            MotionPolicy.suggest(MotionLevel.FULL, false, thermalCelsius = 30f, batteryPercent = 15)
        )
    }

    @Test
    fun `正常状态下尊重用户选择`() {
        assertEquals(
            MotionLevel.FULL,
            MotionPolicy.suggest(MotionLevel.FULL, false, thermalCelsius = 33f, batteryPercent = 80)
        )
        assertEquals(
            MotionLevel.REDUCED,
            MotionPolicy.suggest(MotionLevel.REDUCED, false, thermalCelsius = 33f, batteryPercent = 80)
        )
    }

    @Test
    fun `温度未知时不因温度误降级`() {
        assertEquals(
            MotionLevel.FULL,
            MotionPolicy.suggest(MotionLevel.FULL, false, thermalCelsius = null, batteryPercent = 90)
        )
    }
}

/**
 * 运行模式枚举（需求：只聊/聊天可控/伴随/自动游戏/后台网页/视频控制/插件扩展）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ModeEnumTest {

    @Test
    fun `七种模式齐备且键唯一`() {
        val keys = NekoMode.entries.map { it.key }
        assertEquals(7, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `默认模式是只聊`() {
        assertEquals(NekoMode.CHAT_ONLY, NekoMode.Default)
        assertEquals(NekoMode.Default, NekoMode.fromKey("不存在的模式"))
    }

    @Test
    fun `运行模式配置默认只开启视觉辅助`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekoDatabase::class.java
        ).allowMainThreadQueries().build()

        NekoDatabase.seed(db)
        val visual = db.runModeDao().byMode("visual")!!
        val input = db.runModeDao().byMode("input")!!

        assertTrue("默认只提示", visual.visualAssistEnabled)
        assertFalse("默认不操作", visual.inputAssistEnabled)
        assertTrue(input.inputAssistEnabled)
        assertEquals("visual_only", input.fallbackBehavior)

        assertEquals(2, db.runModeDao().observeAll().first().size)
        db.close()
    }
}
