package com.nekonyan.assistant

import com.nekonyan.assistant.core.plugin.PluginDrawerEntry
import com.nekonyan.assistant.core.plugin.PluginManifest
import com.nekonyan.assistant.core.plugin.PluginPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件策略测试（M15）。重点只有一件事：
 * **安全边界必须由代码拒绝，而不是靠插件作者自觉**。
 */
class PluginPolicyTest {

    private fun manifest(
        id: String = "demo.plugin", name: String = "示例", version: String = "1.0.0",
        permissions: List<String> = listOf("ui.theme"),
        drawer: List<PluginDrawerEntry> = emptyList(),
        themeKeys: List<String> = emptyList(),
        templates: List<com.nekonyan.assistant.core.plugin.PluginPromptTemplate> = emptyList()
    ) = PluginManifest(id, name, version, permissions = permissions, drawerEntries = drawer, themeKeys = themeKeys, promptTemplates = templates)

    @Test
    fun `合法清单通过`() {
        assertNull(PluginPolicy.validate(manifest()))
    }

    @Test
    fun `缺少必填字段被拒`() {
        assertNotNull(PluginPolicy.validate(manifest(id = "")))
        assertNotNull(PluginPolicy.validate(manifest(name = "")))
        assertNotNull(PluginPolicy.validate(manifest(version = "")))
    }

    @Test
    fun `id 格式受限`() {
        assertNotNull(PluginPolicy.validate(manifest(id = "有中文")))
        assertNotNull(PluginPolicy.validate(manifest(id = "a")))
        assertNull(PluginPolicy.validate(manifest(id = "demo.plugin-1")))
    }

    @Test
    fun `未开放的权限被拒`() {
        val m = manifest(permissions = listOf("ui.theme", "system.root"))
        val problem = PluginPolicy.validate(m)
        assertNotNull(problem)
        assertTrue("应指出越权权限：$problem", problem!!.contains("system.root"))
    }

    @Test
    fun `试图覆盖安全边界被整包拒绝`() {
        val keys = listOf("emergency_stop", "permission_entry", "log_readonly", "safety_notice")
        keys.forEach { key ->
            val problem = PluginPolicy.validate(manifest(themeKeys = listOf(key)))
            assertNotNull("$key 必须被拒绝", problem)
            assertTrue(problem!!.contains("安全边界"))
        }
        // 侧边栏项的 id 同样不能冒用保留键
        assertNotNull(PluginPolicy.validate(manifest(drawer = listOf(PluginDrawerEntry("emergency_stop", "假的紧急停止")))))
    }

    @Test
    fun `权限未授权时能力不生效`() {
        val m = manifest(
            permissions = listOf("ui.drawer"),
            drawer = listOf(PluginDrawerEntry("demo.home", "示例页"))
        )
        assertTrue(PluginPolicy.grantedDrawerEntries(m, emptyList()).isEmpty())
        assertEquals(1, PluginPolicy.grantedDrawerEntries(m, listOf("ui.drawer")).size)
        assertFalse(PluginPolicy.isGranted("system.root", listOf("system.root")))
    }

    @Test
    fun `条目数量与内容有上限`() {
        val many = List(PluginPolicy.MAX_ENTRIES + 3) { PluginDrawerEntry("e$it", "项$it") }
        assertNotNull(PluginPolicy.validate(manifest(drawer = many)))
        assertNotNull(PluginPolicy.validate(manifest(drawer = listOf(PluginDrawerEntry("", "")))))
    }

    @Test
    fun `版本比较按数值段而非字符串`() {
        assertTrue(PluginPolicy.versionCompare("1.10.0", "1.9.0") > 0)
        assertTrue(PluginPolicy.canUpdate("1.9.0", "1.10.0"))
        assertFalse(PluginPolicy.canUpdate("1.10.0", "1.9.0"))
        assertEquals(0, PluginPolicy.versionCompare("v1.2.3", "1.2.3"))
        assertTrue("预发布段视为更旧", PluginPolicy.versionCompare("1.0.0-beta", "1.0.0") < 0)
        assertTrue("反向：正式版比预发布新", PluginPolicy.versionCompare("1.0.0", "1.0.0-beta") > 0)
        assertTrue("带数值补丁段仍算更新", PluginPolicy.versionCompare("1.0.1", "1.0") > 0)
        assertTrue("少一段数值则更旧", PluginPolicy.versionCompare("1.0", "1.0.1") < 0)
        assertEquals(0, PluginPolicy.versionCompare("1.0.0", "1.0.0"))
        assertFalse(PluginPolicy.canUpdate("1.0.0", "1.0.0-beta"))
    }
}
