package com.nekonyan.assistant.core.plugin

/**
 * 插件清单（方案 A：**声明式**，不含任何可执行代码）。
 * 插件包 = 一个 `plugin.json`（可选附带图片等资源），宿主按声明去改 UI。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(),
    /** 声明要加的侧边栏项 */
    val drawerEntries: List<PluginDrawerEntry> = emptyList(),
    /** 声明要加的提示词模板 */
    val promptTemplates: List<PluginPromptTemplate> = emptyList(),
    /** 声明要覆盖的主题色键（记录后由主题层决定是否生效） */
    val themeKeys: List<String> = emptyList()
)

data class PluginDrawerEntry(val id: String, val label: String, val body: String = "")

data class PluginPromptTemplate(val id: String, val title: String, val template: String)

/**
 * 插件策略（**纯 Kotlin**，可本机 kotlinc 断言）。
 *
 * 需求原文：「插件可改所有 UI，但**不能破坏紧急停止、权限入口、日志只读与安全提示**」。
 * 这句话在实现上必须是一条**代码里的白名单**，而不是文档里的君子协定：
 *   · [RESERVED_KEYS] 里的键名凡出现在插件清单中，直接判为非法（整包拒绝，不做局部忽略，
 *     否则插件作者会以为"生效了一部分"）；
 *   · [ALLOWED_PERMISSIONS] 之外的权限一律拒绝（权限要单独授权，见需求）；
 *   · 生命周期用 [versionCompare] 判断「可更新」，规则与宿主一致（数值段按数字比，不是字符串比）。
 */
object PluginPolicy {

    /** 宿主的安全边界：这些键不允许被插件声明或覆盖 */
    val RESERVED_KEYS: Set<String> = setOf(
        "emergency_stop", "emergencyStop",
        "permission_entry", "permissionEntry",
        "log_readonly", "logReadonly", "log_delete",
        "safety_notice", "safetyNotice",
        "overlay_kill_switch", "kill_switch"
    )

    /** 宿主开放给插件的权限（需要用户在插件详情里单独授权后才生效） */
    val ALLOWED_PERMISSIONS: Set<String> = setOf(
        "ui.theme",        // 覆盖主题色
        "ui.drawer",       // 增加侧边栏项
        "ui.screen",       // 提供声明式页面
        "prompt.template", // 增加提示词模板
        "config.item"      // 增加配置项
    )

    val MANIFEST_NAME = "plugin.json"
    const val MAX_NAME = 24
    const val MAX_ENTRIES = 12

    /** 校验清单；返回 null 表示通过，否则是给用户看的原因 */
    fun validate(m: PluginManifest): String? {
        if (m.id.isBlank()) return "缺少插件 id"
        if (!m.id.matches(Regex("[A-Za-z0-9._-]{3,64}"))) return "插件 id 只能包含字母数字与 . _ -（3~64 位）"
        if (m.name.isBlank()) return "缺少插件名称"
        if (m.name.length > MAX_NAME) return "插件名称过长（最多 $MAX_NAME 字）"
        if (m.version.isBlank()) return "缺少版本号"

        val bad = m.permissions.filterNot { it in ALLOWED_PERMISSIONS }
        if (bad.isNotEmpty()) return "包含宿主不允许的权限：${bad.joinToString("、")}"

        val reserved = collectKeys(m).filter { it in RESERVED_KEYS }
        if (reserved.isNotEmpty()) {
            return "试图覆盖宿主的安全边界（${reserved.joinToString("、")}）：紧急停止、权限入口、日志只读与安全提示不允许被插件改写"
        }

        if (m.drawerEntries.size > MAX_ENTRIES) return "侧边栏项过多（最多 $MAX_ENTRIES 条）"
        if (m.drawerEntries.any { it.label.isBlank() || it.id.isBlank() }) return "侧边栏项缺少 id 或名称"
        if (m.promptTemplates.any { it.id.isBlank() || it.template.isBlank() }) return "提示词模板缺少 id 或内容"
        return null
    }

    /** 清单里出现过的所有"键"，用于与安全边界比对 */
    fun collectKeys(m: PluginManifest): List<String> =
        buildList {
            addAll(m.themeKeys)
            addAll(m.drawerEntries.map { it.id })
            addAll(m.promptTemplates.map { it.id })
            addAll(m.permissions)
        }

    /** 权限是否已授权：需求要求权限单独授权，未授权的能力不生效 */
    fun isGranted(permission: String, granted: Collection<String>): Boolean =
        permission in ALLOWED_PERMISSIONS && permission in granted

    fun grantedDrawerEntries(m: PluginManifest, granted: Collection<String>): List<PluginDrawerEntry> =
        if (isGranted("ui.drawer", granted)) m.drawerEntries else emptyList()

    fun grantedTemplates(m: PluginManifest, granted: Collection<String>): List<PluginPromptTemplate> =
        if (isGranted("prompt.template", granted)) m.promptTemplates else emptyList()

    /** 是否可更新：远端版本比本地新（数值段按数字比较，1.9 < 1.10） */
    fun canUpdate(local: String, remote: String): Boolean = versionCompare(remote, local) > 0

    fun versionCompare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+')
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull()
            val y = pb.getOrNull(i)?.toIntOrNull()
            when {
                x != null && y != null -> if (x != y) return x.compareTo(y)
                x == null && y != null -> return -1   // 本地是预发布/非数值段，视为更旧
                x != null && y == null -> return 1
                else -> {
                    // 走到这里说明两段都**不是数值**（或其中一方已经结束）
                    val sa = pa.getOrNull(i)
                    val sb = pb.getOrNull(i)
                    if (sa == null && sb == null) continue
                    // b 已结束、a 还多一段非数值：那是预发布标记（1.0.0-beta 的 beta）
                    // → a 比正式版**旧**；反之 a 是正式版则更新。
                    if (sa == null) return 1
                    if (sb == null) return -1
                    val c = sa.compareTo(sb)
                    if (c != 0) return c
                }
            }
        }
        return 0
    }
}
