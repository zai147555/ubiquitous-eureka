package com.nekonyan.assistant.core.yolo

/**
 * YOLO 模型策略（**纯 Kotlin**，可本机 kotlinc 编译并断言）。
 *
 * 对应工作区 `yolo.ds` 里几处最容易写错、又最难在真机上定位的规则：
 *   · 第 46~54 行「场景 → 模型」对照表（普通/战斗/长任务/低功耗/高速/高精度）；
 *   · 第 55 行「切换前检查：内存、存储、电量、温度」，失败要有**人话原因**；
 *   · 第 67~76 行「保留最近 N 个版本 + 自动清理旧版本」，当前版本永不被清；
 *   · 第 29/34/64 行「签名状态 / 导入前校验 / 签名校验」；
 *   · 第 33 行「格式：NCNN（.param + .bin）」的文件命名与解析。
 */

/** 场景（yolo.ds 第 47~54 行） */
enum class YoloScene(val key: String, val label: String) {
    NORMAL("normal", "普通"),
    COMBAT("combat", "战斗/复杂"),
    LONG_TASK("long_task", "长任务"),
    LOW_POWER("low_power", "低功耗"),
    SPEED("speed", "高速"),
    HIGH_ACCURACY("high_accuracy", "高精度");

    companion object {
        val Default = NORMAL
        fun fromKey(key: String?): YoloScene = entries.firstOrNull { it.key == key } ?: Default
    }
}

object ModelPolicy {
    const val V5N = "yolov5n"
    const val V8N = "yolov8n"
    const val V9 = "yolov9"
    const val V11N = "yolo11n"

    /** 内置的那一个（用户指定：11n） */
    const val BUILTIN = V11N

    /** yolo.ds 第 49~54 行的对照表 */
    fun preferredModel(scene: YoloScene): String = when (scene) {
        YoloScene.COMBAT -> V8N
        YoloScene.SPEED -> V5N
        YoloScene.HIGH_ACCURACY -> V9
        YoloScene.NORMAL, YoloScene.LONG_TASK, YoloScene.LOW_POWER -> V11N
    }

    /**
     * 实际该用哪个：优先场景模型 → 没装就沿用当前 → 再退内置 → 最后退任意已装。
     * `installed` 排序后再取，保证结果**确定**（否则 HashSet 顺序会让单测偶发失败）。
     */
    fun resolve(scene: YoloScene, installed: Collection<String>, current: String? = null): String {
        val set = installed.toSet()
        val preferred = preferredModel(scene)
        return when {
            set.contains(preferred) -> preferred
            current != null && set.contains(current) -> current
            set.contains(BUILTIN) -> BUILTIN
            set.isNotEmpty() -> set.sorted().first()
            else -> BUILTIN
        }
    }
}

/** 切换前检查结果 */
data class SwitchCheck(val allowed: Boolean, val reason: String? = null)

/** 切换时机器状态 */
data class SwitchContext(
    val freeStorageBytes: Long,
    val requiredBytes: Long,
    val batteryPercent: Int,
    val temperatureC: Double,
    val cooldownRemainingMs: Long = 0
)

object SwitchGuard {
    /** 除模型本身外再留 50% 余量（解压/临时文件） */
    const val STORAGE_MULTIPLIER = 1.5
    const val MIN_BATTERY_PERCENT = 10
    const val MAX_TEMPERATURE_C = 45.0

    fun check(ctx: SwitchContext): SwitchCheck {
        val need = (ctx.requiredBytes * STORAGE_MULTIPLIER).toLong()
        return when {
            ctx.requiredBytes > 0 && ctx.freeStorageBytes < need ->
                SwitchCheck(false, "存储不足：需要约 ${humanSize(need)} 可用空间，当前 ${humanSize(ctx.freeStorageBytes)}")
            ctx.batteryPercent in 0 until MIN_BATTERY_PERCENT ->
                SwitchCheck(false, "电量过低（${ctx.batteryPercent}%）：低于 $MIN_BATTERY_PERCENT% 不做模型切换")
            ctx.temperatureC > MAX_TEMPERATURE_C ->
                SwitchCheck(false, "温度过高（${"%.1f".format(ctx.temperatureC)}℃）：高于 $MAX_TEMPERATURE_C℃ 不做模型切换")
            ctx.cooldownRemainingMs > 0 ->
                SwitchCheck(false, "冷却中：还需 ${(ctx.cooldownRemainingMs + 999) / 1000} 秒")
            else -> SwitchCheck(true)
        }
    }

    /** 人话大小（1.0 MB 用 MB，1023 KB 不用 1023.0 KB 这种别扭写法） */
    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "未知"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.1f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            bytes >= kb -> "%.0f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }
}

/** 版本（yolo.ds 第 125 行 YoloModelVersion 的关键字段） */
data class VersionInfo(
    val id: String,
    val version: String,
    val createdAt: Long,
    val isCurrent: Boolean = false,
    val deprecated: Boolean = false
)

object VersionRetention {
    const val MIN_KEEP = 2
    const val MAX_KEEP = 5

    fun clampKeep(n: Int): Int = n.coerceIn(MIN_KEEP, MAX_KEEP)

    /**
     * 返回**应当删除**的版本 id。
     * 规则：按创建时间倒序保留 [keep] 个；**当前版本无论多旧都不删**（删了 app 就没模型了）。
     * 时间相同的用 id 兜底排序，避免顺序不确定导致"这次删 A、下次删 B"。
     */
    fun prunePlan(versions: List<VersionInfo>, keep: Int): List<String> {
        val k = clampKeep(keep)
        val sorted = versions.sortedWith(
            compareByDescending<VersionInfo> { it.createdAt }.thenBy { it.id }
        )
        val keepIds = sorted.take(k).map { it.id }.toMutableSet()
        sorted.firstOrNull { it.isCurrent }?.let { keepIds.add(it.id) }
        return sorted.filterNot { it.id in keepIds }.map { it.id }
    }
}

/** 签名校验（yolo.ds 第 29/64 行） */
object SignatureCheck {
    /** 未声明期望签名时**不拦**（内置模型没有服务端签名）；声明了就必须一致 */
    fun matches(expected: String?, actual: String?): Boolean {
        val e = normalize(expected) ?: return true
        val a = normalize(actual) ?: return false
        return e == a
    }

    fun normalize(s: String?): String? =
        s?.trim()?.lowercase()?.removePrefix("sha256:")?.takeIf { it.isNotEmpty() }

    fun isHex64(s: String?): Boolean =
        s != null && s.length == 64 && s.all { it in "0123456789abcdefABCDEF" }
}

/** NCNN 模型文件命名与解析（yolo.ds 第 33 行：NCNN 的 .param + .bin 成对出现） */
object ModelFiles {
    const val PARAM_EXT = "param"
    const val BIN_EXT = "bin"
    const val LABELS_NAME = "labels.txt"
    const val MANIFEST_NAME = "manifest.json"

    fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /** 由文件名推断模型家族；认不出来返回 null（导入时要拦下并提示） */
    fun familyOf(fileName: String): String? {
        val b = baseName(fileName).lowercase()
        return when {
            b.startsWith("yolo11") || b.startsWith("yolov11") -> ModelPolicy.V11N
            b.startsWith("yolov8") || b.startsWith("yolo8") -> ModelPolicy.V8N
            b.startsWith("yolov5") || b.startsWith("yolo5") -> ModelPolicy.V5N
            b.startsWith("yolov9") || b.startsWith("yolo9") -> ModelPolicy.V9
            else -> null
        }
    }

    /** .param 与 .bin 必须同名（只改扩展名）—— 这是 NCNN 的硬要求 */
    fun pairsWith(param: String, bin: String): Boolean =
        baseName(param).equals(baseName(bin), ignoreCase = true)

    /**
     * 从 ncnn 的 .param 文本里读输入尺寸。
     * 首层形如：`Input            images                   0 1 images 0=1 1=3 2=640 3=640`
     * 读不到就返回 null，让调用方回退到 640，而不是猜一个数字。
     */
    fun detectInputSize(paramText: String): Int? {
        val re = Regex("""(?:^|\s)2=(\d+)""")
        for (line in paramText.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("Input")) continue
            re.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) return it }
        }
        return null
    }

    /** 类别数 = labels.txt 里非空、非注释行数 */
    fun countLabels(labelsText: String): Int =
        labelsText.lineSequence().count { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
}
