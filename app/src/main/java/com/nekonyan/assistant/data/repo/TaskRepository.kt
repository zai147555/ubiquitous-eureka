package com.nekonyan.assistant.data.repo

import com.nekonyan.assistant.data.db.NekoMode
import com.nekonyan.assistant.data.db.Task
import com.nekonyan.assistant.data.db.TaskDao
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import com.nekonyan.assistant.core.util.NekoMode

/**
 * 任务仓库（需求原文）：
 *   · 任务必须填写「任务名称 + 任务消息」；
 *   · 点击任务自动把任务消息发给 AI，无需手动输入；
 *   · 按模式区分，各模式独立列表；
 *   · 任务列表显示 名称、消息摘要、模式、状态、最后执行时间。
 *
 * 这里的校验（名称与消息都非空）对应需求里的"必须填写"，
 * 放在仓库层而不是界面层，保证任何入口都绕不过去。
 */
class TaskRepository(private val dao: TaskDao) {

    /** 新建/更新任务时如果名称为空，用消息首行兜底（不允许无名任务） */
    fun observeAll(): Flow<List<Task>> = dao.observeAll()

    fun observeByMode(mode: NekoMode): Flow<List<Task>> = dao.observeByMode(mode.key)

    suspend fun byId(id: String): Task? = dao.byId(id)

    /**
     * 创建任务。
     * @throws IllegalArgumentException 名称或消息为空时抛出（需求：必须填写）
     */
    suspend fun create(mode: NekoMode, name: String, message: String): String {
        val n = name.trim()
        val m = message.trim()
        require(n.isNotEmpty()) { "任务名称不能为空" }
        require(m.isNotEmpty()) { "任务消息不能为空" }

        val id = "task-" + UUID.randomUUID().toString().take(8)
        dao.upsert(Task(id = id, name = n, mode = mode.key, message = m))
        return id
    }

    suspend fun update(task: Task, name: String, message: String): Task {
        val n = name.trim()
        val m = message.trim()
        require(n.isNotEmpty()) { "任务名称不能为空" }
        require(m.isNotEmpty()) { "任务消息不能为空" }
        val updated = task.copy(name = n, message = m)
        dao.update(updated)
        return updated
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(task: Task) = dao.delete(task)

    /** 需求：记录最后执行时间与状态 */
    suspend fun markRun(id: String, status: String) =
        dao.markRun(id, System.currentTimeMillis(), status)

    /**
     * 生成要发送给 AI 的任务消息。
     * 需求：任务消息支持模板变量，可引用知识库、地图、物资、配枪、理包、跟随。
     * 这里先把变量替换做成可测的纯逻辑，具体数据源在后续里程碑注入。
     */
    fun renderMessage(task: Task, variables: Map<String, String> = emptyMap()): String {
        var out = task.message
        variables.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }
}
