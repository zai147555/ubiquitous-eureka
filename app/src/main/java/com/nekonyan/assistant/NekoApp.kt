package com.nekonyan.assistant

import android.app.Application
import android.content.Context
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.core.env.EnvironmentChecker
import com.nekonyan.assistant.core.env.ThermalMonitor
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 职责（尽量轻，避免拖慢冷启动）：
 *   ① 建立日志（需求：日志只读、脱敏、保留期可配）
 *   ② 打开数据库 + 幂等种子数据（AI 知识库、默认人格、各模式保守权限）
 *   ③ 环境检测（需求：检测缺失时给出引导、自动降级、记录日志）
 *   ④ 温度/电量监控（需求：温度分级、电量分级）
 */
class NekoApp : Application() {

    /** 应用级协程作用域：不随任何界面销毁而取消 */
    val appScope = CoroutineScope(SupervisorJob() + NekoDatabase.writeDispatcher)

    override fun onCreate() {
        super.onCreate()
        instance = this

        NekoLog.init(this)
        NekoLog.info(NekoLog.MODULE_UI, "app_start", "猫娘助手启动 v${BuildConfig.VERSION_NAME}")

        // 数据库与种子数据（幂等）
        appScope.launch {
            runCatching {
                NekoDatabase.seed(NekoDatabase.get(this@NekoApp))
            }.onFailure {
                NekoLog.error(NekoLog.MODULE_STORE, "seed_failed", it.message ?: "unknown")
            }
        }

        // 环境检测 + 温度监控
        appScope.launch {
            runCatching { ThermalMonitor.start(this@NekoApp) }
                .onFailure { NekoLog.warn(NekoLog.MODULE_UI, "thermal_monitor_failed", it.message ?: "") }
            runCatching {
                val r = EnvironmentChecker.checkAll(this@NekoApp)
                NekoLog.info(NekoLog.MODULE_UI, "env_check", r.summary())
            }.onFailure {
                NekoLog.warn(NekoLog.MODULE_UI, "env_check_failed", it.message ?: "")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: NekoApp? = null

        fun get(): NekoApp = instance
            ?: error("NekoApp 尚未初始化（不应在 Application.onCreate 之前访问）")

        fun context(): Context = get().applicationContext
    }
}
