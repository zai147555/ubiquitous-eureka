package com.nekonyan.assistant.data.repo

import android.content.Context
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.AIPersona
import com.nekonyan.assistant.data.db.PersonaDao
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 人格仓库（`修改.ds` 第一项）。
 *
 * 两个概念刻意分开，别混：
 *   · **默认人格**（`AIPersona.isDefault`，库里互斥）—— 新建会话时用它；
 *   · **当前人格**（本类用 SharedPreferences 记 id）—— 用户此刻选中的那个。
 * 合成一条"当前用谁"的规则：prefs 指定的 → 默认人格 → 列表第一条。
 * 这样删掉当前人格、或数据库被重建，都不会出现"没有人格"的空档。
 */
class PersonaRepository(
    private val dao: PersonaDao,
    context: Context
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun observeAll(): Flow<List<AIPersona>> = dao.observeAll()

    suspend fun all(): List<AIPersona> = dao.all()

    suspend fun byId(id: String): AIPersona? = dao.byId(id)

    /** 当前生效的人格（见类注释的合成规则） */
    suspend fun currentPersona(): AIPersona? {
        val id = prefs.getString(KEY_CURRENT_ID, null)
        return (id?.let { dao.byId(it) })
            ?: dao.defaultPersona()
            ?: dao.all().firstOrNull()
    }

    /** 需求：使用时把「名称 + 描述」拼进系统提示词 */
    suspend fun promptFragment(): String =
        currentPersona()?.let { p ->
            buildString {
                append(p.name)
                if (p.description.isNotBlank()) append("：").append(p.description)
            }
        } ?: ""

    /** 需求：支持新增 */
    suspend fun create(name: String, description: String): AIPersona? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val persona = AIPersona(
            id = UUID.randomUUID().toString(),
            name = n,
            description = description.trim(),
            isDefault = dao.all().isEmpty()      // 第一条自动成为默认，避免"一个默认都没有"
        )
        dao.upsert(persona)
        if (persona.isDefault) prefs.edit().putString(KEY_CURRENT_ID, persona.id).apply()
        NekoLog.info(NekoLog.MODULE_AI, "persona_create", n)
        return persona
    }

    /** 需求：支持编辑（只改名称与描述 —— 字段就这两个） */
    suspend fun update(persona: AIPersona, name: String, description: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        dao.upsert(persona.copy(name = n, description = description.trim()))
        NekoLog.info(NekoLog.MODULE_AI, "persona_update", n)
    }

    /** 需求：支持复制 */
    suspend fun duplicate(persona: AIPersona): AIPersona {
        val copy = persona.copy(
            id = UUID.randomUUID().toString(),
            name = "${persona.name} 副本",
            isDefault = false,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(copy)
        NekoLog.info(NekoLog.MODULE_AI, "persona_duplicate", "${persona.name} → ${copy.name}")
        return copy
    }

    /** 需求：支持删除（默认人格与最后一条不允许删，否则会没有人格可用） */
    suspend fun delete(persona: AIPersona): String? {
        val all = dao.all()
        if (all.size <= 1) return "至少要保留一个人格"
        if (persona.isDefault) return "默认人格不能删除，请先把别的设为默认"
        dao.delete(persona)
        if (prefs.getString(KEY_CURRENT_ID, null) == persona.id) {
            prefs.edit().remove(KEY_CURRENT_ID).apply()
        }
        NekoLog.info(NekoLog.MODULE_AI, "persona_delete", persona.name)
        return null
    }

    /** 需求：设为默认（互斥） */
    suspend fun setDefault(persona: AIPersona) {
        dao.setDefault(persona.id)
        NekoLog.info(NekoLog.MODULE_AI, "persona_set_default", persona.name)
    }

    /** 需求：切换当前人格 + 记录日志（日志只读，写进现有日志表） */
    suspend fun switchCurrent(persona: AIPersona) {
        val from = currentPersona()?.name ?: "无"
        prefs.edit().putString(KEY_CURRENT_ID, persona.id).apply()
        NekoLog.info(NekoLog.MODULE_AI, "persona_switch", "$from → ${persona.name}")
    }

    fun currentId(): String? = prefs.getString(KEY_CURRENT_ID, null)

    private companion object {
        const val PREF = "nekonyan_persona"
        const val KEY_CURRENT_ID = "current_persona_id"
    }
}
