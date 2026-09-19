package com.nekonyan.assistant

import com.nekonyan.assistant.core.perm.PermissionGuide
import com.nekonyan.assistant.core.perm.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限引导的纯逻辑测试（`修改.ds` 第五项）。
 *
 * 为什么值得测：进度算错会长这样 —— "明明全授权了还提示还差 1 项必需权限"，
 * 用户会反复去点那个按钮，而问题其实在计数逻辑。
 */
class PermissionGuideTest {

    @Test
    fun `清单覆盖需求要求的九项且键唯一`() {
        val keys = PermissionGuide.ITEMS.map { it.key }
        assertEquals(9, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(PermissionGuide.KEY_NOTIFICATION, keys.first())
        assertEquals(PermissionGuide.KEY_AUTOSTART, keys.last())
    }

    @Test
    fun `每项都有用途说明且至少有五项必需`() {
        PermissionGuide.ITEMS.forEach {
            assertTrue("「${it.title}」缺少用途说明", it.purpose.length > 8)
        }
        assertTrue(PermissionGuide.ITEMS.count { it.required } >= 5)
    }

    @Test
    fun `全部授权时进度为完成`() {
        val states = PermissionGuide.ITEMS.associate { it.key to PermissionState.GRANTED }
        val p = PermissionGuide.progress(states)
        assertTrue(p.requiredDone)
        assertEquals(100, p.percent)
        assertEquals(p.total, p.grantedAll)
        assertTrue(p.summary.contains("必需项已完成"))
    }

    @Test
    fun `都没授权时提示还差几项必需权限`() {
        val p = PermissionGuide.progress(emptyMap())
        assertFalse(p.requiredDone)
        val required = PermissionGuide.ITEMS.count { it.required }
        assertTrue("实际文案：${p.summary}", p.summary.contains("还差 $required 项必需权限"))
        assertEquals(0, p.percent)
    }

    @Test
    fun `需手动开启不等于已授权`() {
        val states = PermissionGuide.ITEMS.associate { it.key to PermissionState.MANUAL }
        assertFalse(PermissionGuide.progress(states).requiredDone)
        assertFalse(PermissionGuide.isGranted(PermissionState.MANUAL))
        assertFalse(PermissionGuide.isGranted(PermissionState.NOT_APPLICABLE))
        assertTrue(PermissionGuide.isGranted(PermissionState.GRANTED))
    }

    @Test
    fun `只差一项必需时进度如实反映`() {
        val states = PermissionGuide.ITEMS.associate { it.key to PermissionState.GRANTED }.toMutableMap()
        val firstRequired = PermissionGuide.ITEMS.first { it.required }.key
        states[firstRequired] = PermissionState.DENIED
        val p = PermissionGuide.progress(states)
        assertFalse(p.requiredDone)
        assertTrue(p.summary.contains("还差 1 项必需权限"))
    }

    @Test
    fun `每一项都有降级说明（未授权时界面要能提示）`() {
        PermissionGuide.ITEMS.forEach {
            val hint = PermissionGuide.degradationHint(it.key)
            assertTrue("「${it.title}」缺降级说明", hint.isNotBlank())
        }
        assertTrue(PermissionGuide.degradationHint("未知键").isNotBlank())
    }
}
