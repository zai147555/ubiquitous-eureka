package com.nekonyan.assistant.core.log

import android.content.Context
import android.util.Log
import com.nekonyan.assistant.data.db.LogEntry
import com.nekonyan.assistant.data.db.NekoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志系统（需求：日志级别 / 字段 / 模块 / 脱敏 / **只读不可删除** / 保留期与大小可配）
 *
 * 关键约束：
 *   ① 只读：本类只写不删；对外不暴露任何删除接口。仅按保留期做容量轮转。
 *   ② 脱敏：API Key、Token、密码、验证码、手机号、身份证在写入前替换为掩码。
 *   ③ 不阻塞：写日志走内存队列 + 单线程消费，推理线程/UI 线程调用不会卡。
 *   ④ 不丢：队列满时丢弃「DEBUG」级（最不重要），并计入丢弃计数，绝不丢 ERROR 以上。
 */
object NekoLog {

    // 模块常量（需求：日志模块清单）
    const val MODULE_UI = LogEntry.MODULE_UI
    const val MODULE_AI = LogEntry.MODULE_AI
    const val MODULE_YOLO = LogEntry.MODULE_YOLO
    const val MODULE_SHIZUKU = LogEntry.MODULE_SHIZUKU
    const val MODULE_PROJECTION = LogEntry.MODULE_PROJECTION
    const val MODULE_NET = LogEntry.MODULE_NET
    const val MODULE_STORE = LogEntry.MODULE_STORE
    const val MODULE_PERM = LogEntry.MODULE_PERM
    const val MODULE_TASK = LogEntry.MODULE_TASK
    const val MODULE_GAME = LogEntry.MODULE_GAME
    const val MODULE_PLUGIN = LogEntry.MODULE_PLUGIN
    const val MODULE_SKILL = LogEntry.MODULE_SKILL
    const val MODULE_SYNC = LogEntry.MODULE_SYNC
    const val MODULE_UPDATE = LogEntry.MODULE_UPDATE
    const val MODULE_SECURITY = LogEntry.MODULE_SECURITY

    private const val TAG = "NekoLog"
    private const val QUEUE_CAPACITY = 512
    private const val ROTATE_CHECK_EVERY = 200

    private val scope = CoroutineScope(SupervisorJob() + NekoDatabase.writeDispatcher)
    private val queue = Channel<LogEntry>(QUEUE_CAPACITY)

    private val dropped = AtomicLong(0)
    private val written = AtomicLong(0)

    @Volatile private var appContext: Context? = null

    /** 需求：日志保留天数可配（默认 7 天） */
    @Volatile var retentionDays: Int = 7
    /** 需求：日志大小上限可配（按条数近似控制，默认 20000 条） */
    @Volatile var maxEntries: Int = 20_000

    /** 需求：日志上传可选，默认关闭 */
    @Volatile var uploadEnabled: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            for (entry in queue) {
                val ctx = appContext ?: continue
                runCatching {
                    val dao = NekoDatabase.get(ctx).logDao()
                    dao.insert(entry)
                    val n = written.incrementAndGet()
                    if (n % ROTATE_CHECK_EVERY == 0L) rotate(ctx, dao)
                }.onFailure { Log.w(TAG, "写入日志失败: ${it.message}") }
            }
        }
    }

    /** 保留期 + 条数双上限轮转（这是容量管理，不是"删除日志"功能） */
    private suspend fun rotate(ctx: Context, dao: com.nekonyan.assistant.data.db.LogDao) {
        val before = System.currentTimeMillis() - retentionDays * 24L * 3600_000L
        dao.rotateBefore(before)
        val total = dao.count()
        if (total > maxEntries) {
            val cut = System.currentTimeMillis() - (retentionDays * 24L * 3600_000L) / 2
            dao.rotateBefore(cut)
        }
    }

    // ---------------- 对外写入接口 ----------------

    fun debug(module: String, event: String, detail: String = "") =
        write(LogEntry.LEVEL_DEBUG, module, event, detail)

    fun info(module: String, event: String, detail: String = "") =
        write(LogEntry.LEVEL_INFO, module, event, detail)

    fun warn(module: String, event: String, detail: String = "") =
        write(LogEntry.LEVEL_WARN, module, event, detail)

    fun error(module: String, event: String, detail: String = "") =
        write(LogEntry.LEVEL_ERROR, module, event, detail)

    fun fatal(module: String, event: String, detail: String = "") =
        write(LogEntry.LEVEL_FATAL, module, event, detail)

    private fun write(level: String, module: String, event: String, detail: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            module = module,
            event = event,
            detail = Redactor.redact(detail),
            deviceId = "" // 需求：不在日志中输出用户标识明文
        )
        val queued = queue.trySend(entry).isSuccess
        if (!queued) {
            // 队列满：DEBUG 直接丢；ERROR 以上退回直接打印，绝不静默丢失
            if (level == LogEntry.LEVEL_DEBUG) {
                dropped.incrementAndGet()
            } else {
                Log.w(TAG, "[$level][$module] $event ${entry.detail}")
            }
        }
    }

    fun droppedCount(): Long = dropped.get()
}
