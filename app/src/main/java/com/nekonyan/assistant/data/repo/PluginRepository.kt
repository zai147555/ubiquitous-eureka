package com.nekonyan.assistant.data.repo

import android.content.Context
import android.net.Uri
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.plugin.PluginDrawerEntry
import com.nekonyan.assistant.core.plugin.PluginManifest
import com.nekonyan.assistant.core.plugin.PluginPolicy
import com.nekonyan.assistant.core.plugin.PluginPromptTemplate
import com.nekonyan.assistant.data.db.PluginRecordDao
import com.nekonyan.assistant.data.db.PluginRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件仓库（M15 · 方案 A：**声明式**，不加载任何可执行代码）。
 *
 * 插件包 = 一个 `plugin.json`（宿主从文件选择器读入）：
 * ```json
 * { "id":"demo.theme","name":"示例","version":"1.0.0","author":"you",
 *   "description":"...","permissions":["ui.theme","ui.drawer"],
 *   "themeKeys":["neko.primary"],                      // 想覆盖的主题键
 *   "drawerEntries":[{"id":"demo.home","label":"示例页","body":"内容"}],
 *   "promptTemplates":[{"id":"demo.t","title":"总结","template":"请总结：{{text}}"}] }
 * ```
 * 安全边界不由这里把关，而是 [PluginPolicy.validate]（纯逻辑、有单测）——
 * 仓库只负责"读文件、解析、落库、写日志"。
 */
class PluginRepository(
    private val dao: PluginRecordDao,
    private val context: Context
) {

    fun observeAll(): Flow<List<PluginRecordEntity>> = dao.observeAll()

    suspend fun all(): List<PluginRecordEntity> = dao.all()

    /** 导入：读清单 → 解析 → 策略校验 → 落库（同名插件按 id 覆盖） */
    suspend fun importFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return@withContext "读不到文件内容（选择 plugin.json）"

        val manifest = parse(text) ?: return@withContext "不是合法的插件清单（JSON 解析失败）"
        val problem = PluginPolicy.validate(manifest)
        if (problem != null) {
            NekoLog.warn(NekoLog.MODULE_PLUGIN, "plugin_rejected", "${manifest.id}: $problem")
            return@withContext problem
        }

        val existing = dao.byId(manifest.id)
        val granted = existing?.grantedPermissions.orEmpty()
        dao.upsert(
            PluginRecordEntity(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                author = manifest.author,
                description = manifest.description,
                grantedPermissions = granted,       // 权限需用户单独授权：升级不自动扩权
                enabled = existing?.enabled ?: false,
                manifestJson = text,
                source = "import",
                installedAt = existing?.installedAt ?: System.currentTimeMillis()
            )
        )
        NekoLog.info(NekoLog.MODULE_PLUGIN, if (existing == null) "plugin_install" else "plugin_update",
            "${manifest.name} ${manifest.version}")
        null
    }

    suspend fun setEnabled(record: PluginRecordEntity, enabled: Boolean) {
        dao.setEnabled(record.id, enabled, System.currentTimeMillis())
        NekoLog.info(NekoLog.MODULE_PLUGIN, if (enabled) "plugin_enable" else "plugin_disable", record.name)
    }

    /** 需求：权限单独授权 */
    suspend fun setPermission(record: PluginRecordEntity, permission: String, granted: Boolean) {
        if (permission !in PluginPolicy.ALLOWED_PERMISSIONS) return
        val current = record.grantedList().toMutableSet()
        if (granted) current.add(permission) else current.remove(permission)
        dao.setGranted(record.id, current.joinToString(","), System.currentTimeMillis())
        NekoLog.info(NekoLog.MODULE_PLUGIN, if (granted) "plugin_grant" else "plugin_revoke",
            "${record.name} · $permission")
    }

    suspend fun delete(record: PluginRecordEntity) {
        dao.delete(record.id)
        NekoLog.info(NekoLog.MODULE_PLUGIN, "plugin_uninstall", record.name)
    }

    /** 解析插件清单（org.json）；字段缺失用默认值，绝不抛异常给界面 */
    fun parse(text: String): PluginManifest? = runCatching {
        val o = JSONObject(text)
        PluginManifest(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            version = o.optString("version", ""),
            author = o.optString("author", ""),
            description = o.optString("description", ""),
            permissions = o.optJSONArray("permissions").toStringList(),
            drawerEntries = o.optJSONArray("drawerEntries").toDrawerEntries(),
            promptTemplates = o.optJSONArray("promptTemplates").toTemplates(),
            themeKeys = o.optJSONArray("themeKeys").toStringList()
        )
    }.getOrNull()

    /** 启用且已授权的插件所提供的侧边栏项（供宿主渲染） */
    suspend fun activeDrawerEntries(): List<Pair<PluginRecordEntity, PluginDrawerEntry>> =
        dao.all().filter { it.enabled }.flatMap { record ->
            val manifest = parse(record.manifestJson) ?: return@flatMap emptyList()
            PluginPolicy.grantedDrawerEntries(manifest, record.grantedList()).map { record to it }
        }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    private fun JSONArray?.toDrawerEntries(): List<PluginDrawerEntry> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let {
                PluginDrawerEntry(
                    id = it.optString("id", ""),
                    label = it.optString("label", ""),
                    body = it.optString("body", "")
                )
            }
        }
    }

    private fun JSONArray?.toTemplates(): List<PluginPromptTemplate> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let {
                PluginPromptTemplate(
                    id = it.optString("id", ""),
                    title = it.optString("title", ""),
                    template = it.optString("template", "")
                )
            }
        }
    }
}
