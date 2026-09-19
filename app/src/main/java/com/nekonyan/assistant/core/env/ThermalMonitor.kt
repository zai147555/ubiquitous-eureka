package com.nekonyan.assistant.core.env

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 功耗与发热管理（需求原文）：
 *   温度分级：<38℃ 全速；38~42℃ 降频；42~45℃ 只保留核心识别；>45℃ 暂停非关键任务
 *   电量分级：<30% 降频；<20% 只提示不操作；<10% 停止
 *   NPU 优先、动态抽帧、区域裁剪、结果缓存等策略由此处的等级驱动
 *
 * 温度来源：优先读系统热区（Android 10+ 的 THERMAL_STATUS 与 sysfs），
 * 读不到时返回 null —— **不猜温度**，降级为「按热状态等级判断」。
 */
object ThermalMonitor {

    private const val TAG = "ThermalMonitor"

    /** 需求：温度分级阈值（摄氏度） */
    const val TEMP_FULL = 38f
    const val TEMP_THROTTLE = 42f
    const val TEMP_CORE_ONLY = 45f

    /** 需求：电量分级阈值（百分比） */
    const val BATT_THROTTLE = 30
    const val BATT_HINT_ONLY = 20
    const val BATT_STOP = 10

    /** 性能档位：由温度与电量共同决定 */
    enum class PerfLevel(val label: String) {
        FULL("全速"),
        THROTTLED("降频"),
        CORE_ONLY("仅核心识别"),
        PAUSED("暂停非关键任务")
    }

    data class ThermalState(
        val celsius: Float?,
        val batteryPercent: Int,
        val charging: Boolean,
        val thermalStatus: Int,
        val perfLevel: PerfLevel
    ) {
        fun summary(): String = buildString {
            append("温度=").append(celsius?.let { "%.1f℃".format(it) } ?: "未知")
            append(" 电量=").append(batteryPercent).append('%')
            append(if (charging) " (充电中)" else "")
            append(" 档位=").append(perfLevel.label)
        }
    }

    private val _state = MutableStateFlow(
        ThermalState(null, 100, false, PowerManager.THERMAL_STATUS_NONE, PerfLevel.FULL)
    )
    val state: StateFlow<ThermalState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 常见热区路径（不同厂商差异很大，逐一尝试；全部失败即视为未知） */
    private val thermalZones = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp",
        "/sys/class/hwmon/hwmon0/temp1_input"
    )

    fun start(context: Context) {
        val ctx = context.applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        scope.launch {
            while (true) {
                runCatching {
                    val t = readCelsius()
                    val batt = readBattery(ctx)
                    val status = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                    val level = evaluate(t, batt.first, status)
                    val newState = ThermalState(t, batt.first, batt.second, status, level)
                    val old = _state.value
                    _state.value = newState
                    if (old.perfLevel != level) {
                        NekoLog.warn(
                            NekoLog.MODULE_UI, "perf_level_changed",
                            "${old.perfLevel.label} → ${level.label}; ${newState.summary()}"
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private const val POLL_INTERVAL_MS = 30_000L

    /** 需求：温度、电量、性能数据记录日志；支持用户查看实时温度与功耗 */
    fun readCelsius(): Float? {
        for (path in thermalZones) {
            val f = File(path)
            if (!f.canRead()) continue
            val raw = runCatching { f.readText().trim() }.getOrNull() ?: continue
            val v = raw.toFloatOrNull() ?: continue
            // 不同内核单位不同：>1000 视为毫摄氏度
            val c = if (v > 1000f) v / 1000f else v
            if (c > -40f && c < 150f) return c
        }
        return null
    }

    private fun readBattery(ctx: Context): Pair<Int, Boolean> {
        return runCatching {
            val intent: Intent? = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            pct to charging
        }.getOrElse { 100 to false }
    }

    /**
     * 综合评估性能档位。系统热状态（Android 10+）比读 sysfs 更可靠，
     * 因此两者取更保守的那个。
     */
    fun evaluate(celsius: Float?, batteryPercent: Int, thermalStatus: Int): PerfLevel {
        // 电量优先级最高：需求 <10% 停止
        if (batteryPercent < BATT_STOP) return PerfLevel.PAUSED

        val byTemp = when {
            celsius == null -> null
            celsius >= TEMP_CORE_ONLY -> PerfLevel.PAUSED
            celsius >= TEMP_THROTTLE -> PerfLevel.CORE_ONLY
            celsius >= TEMP_FULL -> PerfLevel.THROTTLED
            else -> PerfLevel.FULL
        }
        val bySystem = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> PerfLevel.CORE_ONLY
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> PerfLevel.THROTTLED
            else -> null
        }
        val byBattery = when {
            batteryPercent < BATT_HINT_ONLY -> PerfLevel.CORE_ONLY
            batteryPercent < BATT_THROTTLE -> PerfLevel.THROTTLED
            else -> null
        }
        return listOfNotNull(byTemp, bySystem, byBattery, PerfLevel.FULL)
            .maxByOrNull { it.ordinal } ?: PerfLevel.FULL
    }

    /** 需求：超过阈值提示用户 */
    fun shouldNotifyUser(s: ThermalState): Boolean =
        s.perfLevel != PerfLevel.FULL || (s.celsius ?: 0f) >= TEMP_FULL

    fun sdkInt(): Int = Build.VERSION.SDK_INT
}
