package com.nekonyan.assistant.core.env

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.EnvironmentCheckRecord
import com.nekonyan.assistant.data.db.NekoDatabase

/**
 * 环境检测与降级（需求：一键诊断，检测权限、Shizuku、模型、网络、温度；
 * 检测缺失时给出引导，自动降级，降级后仍能部分运行，记录日志）
 *
 * 重要：本类**只报告事实**，不申请权限、不弹窗、不改配置 ——
 * 界面据此给出引导，避免"静默自动申请权限"这种让用户困惑的行为。
 */
object EnvironmentChecker {

    const val CHECK_SYSTEM = "system"
    const val CHECK_RUNTIME = "runtime"
    const val CHECK_INFERENCE = "inference"
    const val CHECK_AI = "ai"
    const val CHECK_STORAGE = "storage"
    const val CHECK_PERMISSION = "permission"
    const val CHECK_HARDWARE = "hardware"
    const val CHECK_NETWORK = "network"

    data class Item(
        val name: String,
        val ok: Boolean,
        /** 缺失/异常时给用户看的一句话建议 */
        val suggestion: String = "",
        /** 是否已自动降级（降级后仍能部分运行） */
        val degraded: Boolean = false
    )

    data class Report(val items: List<Item>) {
        val missing: List<Item> get() = items.filter { !it.ok }
        val degraded: List<Item> get() = items.filter { it.degraded }
        val healthy: Boolean get() = missing.isEmpty()

        fun summary(): String = buildString {
            append("共 ").append(items.size).append(" 项，")
            append("正常 ").append(items.count { it.ok }).append("，")
            append("缺失 ").append(missing.size)
            if (degraded.isNotEmpty()) append("，降级 ").append(degraded.size)
            if (missing.isNotEmpty()) {
                append("；缺失: ").append(missing.joinToString(",") { it.name })
            }
        }
    }

    suspend fun checkAll(context: Context): Report {
        val items = buildList {
            add(checkSystem(context))
            add(checkRuntime(context))
            addAll(checkPermissions(context))
            add(checkStorage(context))
            add(checkNetwork(context))
            add(checkInference(context))
            add(checkAI(context))
            add(checkHardware())
        }
        val report = Report(items)

        // 需求：记录日志（一键诊断结果可回溯）
        runCatching {
            NekoDatabase.get(context).environmentDao().insert(
                EnvironmentCheckRecord(
                    checkType = "all",
                    result = if (report.healthy) "ok" else "missing",
                    missingItems = report.missing.joinToString(",") { it.name },
                    degraded = report.degraded.isNotEmpty()
                )
            )
        }
        report.missing.forEach {
            NekoLog.warn(NekoLog.MODULE_PERM, "env_missing", "${it.name}: ${it.suggestion}")
        }
        return report
    }

    // ---------------- 单项检测 ----------------

    private fun checkSystem(context: Context): Item {
        val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return Item(
            name = "系统版本",
            ok = ok,
            suggestion = if (ok) "" else "需求要求 Android 12+（当前 API ${Build.VERSION.SDK_INT}）"
        )
    }

    private fun checkRuntime(context: Context): Item {
        // 前台服务 + 通知：长任务与录屏的前提
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return Item(
            name = "通知权限",
            ok = notif,
            suggestion = if (notif) "" else "前台服务需要通知权限，否则长任务会被系统回收",
            degraded = !notif
        )
    }

    private fun checkPermissions(context: Context): List<Item> = listOf(
        permission(context, "悬浮窗", Settings.canDrawOverlays(context),
            "前往系统设置开启「显示在其他应用上层」"),
        accessibility(context),
        projection(context),
        permission(context, "录音", granted(context, Manifest.permission.RECORD_AUDIO),
            "语音输入与语音唤醒需要录音权限")
    )

    private fun permission(context: Context, name: String, ok: Boolean, hint: String) =
        Item(name = name, ok = ok, suggestion = if (ok) "" else hint)

    private fun granted(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /** 无障碍：通过已启用的服务列表判断（不主动拉起设置页） */
    private fun accessibility(context: Context): Item {
        val enabled = runCatching {
            val expected = "${context.packageName}/.service.NekoAccessibilityService"
            val flat = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            flat.split(':').any { it.equals(expected, ignoreCase = true) }
        }.getOrDefault(false)
        return Item(
            name = "无障碍",
            ok = enabled,
            suggestion = if (enabled) "" else "爬楼总结、控件树读取需要无障碍权限；未开启时回退为纯截图识别",
            degraded = !enabled
        )
    }

    /** MediaProjection 无法在后台探测，只能在用户授权时拿到；此处报告"未验证" */
    private fun projection(context: Context): Item =
        Item(
            name = "录屏授权",
            ok = false,
            suggestion = "首次抓屏时系统会弹窗授权；被系统终止后需重新授权",
            degraded = true
        )

    private fun checkStorage(context: Context): Item {
        val free = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val mb = free / 1024 / 1024
        val ok = mb > 200
        return Item(
            name = "存储空间",
            ok = ok,
            suggestion = if (ok) "" else "可用空间仅 ${mb}MB，模型与采样队列会失败",
            degraded = !ok
        )
    }

    private fun checkNetwork(context: Context): Item {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return Item(
            name = "网络",
            ok = online,
            // 需求：网络断开时本地 YOLO 继续，DeepSeek 不可用回退规则引擎
            suggestion = if (online) "" else "离线：本地 YOLO 仍可用，AI 对话将回退规则引擎",
            degraded = !online
        )
    }

    /** 模型文件：检查热更新目录与内置基线是否至少有一个可用 */
    private fun checkInference(context: Context): Item {
        val current = java.io.File(context.filesDir, "models/current/yolov8n.param")
        val baseline = runCatching {
            context.assets.list("models/v1")?.isNotEmpty() == true
        }.getOrDefault(false)
        val ok = current.exists() || baseline
        return Item(
            name = "本地模型",
            ok = ok,
            suggestion = if (ok) "" else "未找到模型：请把 yolov8n.param/.bin 放入 assets/models/v1/ 或等待热更新",
            degraded = !ok
        )
    }

    private fun checkAI(context: Context): Item {
        val ok = SecurityStore.hasApiKey(context)
        return Item(
            name = "DeepSeek API Key",
            ok = ok,
            suggestion = if (ok) "" else "在「配置」页填入 API Key（加密存于 Keystore）",
            degraded = !ok
        )
    }

    /** NPU 优先（需求）：这里只做能力记录，实际调度在 M4 推理层 */
    private fun checkHardware(): Item {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val ok = abi.contains("arm64")
        return Item(
            name = "CPU 架构",
            ok = ok,
            suggestion = if (ok) "" else "当前 $abi，NCNN 将回退通用实现，性能下降",
            degraded = !ok
        )
    }
}
