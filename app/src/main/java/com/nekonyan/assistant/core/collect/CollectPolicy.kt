package com.nekonyan.assistant.core.collect

/**
 * 「采集训练数据」的判定逻辑（纯函数，不依赖 Android，便于单元测试）。
 *
 * 为什么把判定单独抽出来测：这段逻辑决定"什么时候把用户的屏幕内容传出去"，
 * 判错的代价不是崩溃而是**隐私与流量**：
 *   · 判定太松 → 2 帧/秒无脑上传 ≈ 1.4 GB/小时，服务器直接被打爆；
 *   · 判定失效（该停不停）→ 用户以为关了还在传。
 * 所以开关、间隔、每日上限、仅 Wi-Fi、去重全部在这里显式判定，并用断言锁住。
 *
 * ★ 底线：默认关闭；界面上必须能看到"已采集多少 / 多大"，随时可停。
 *   不做任何形式的静默采集。
 */
object CollectPolicy {

    /** 采样间隔的最小值：再密就是拿用户流量和服务器换噪声（相邻帧几乎一样） */
    const val MIN_INTERVAL_MS = 5_000L

    /** 近似去重：64 位 dHash 的汉明距离 ≤ 此值视为同一画面 */
    const val DUP_HAMMING_THRESHOLD = 6

    data class Rules(
        val enabled: Boolean = false,
        val intervalMs: Long = 30_000L,
        val dailyCapCount: Int = 500,
        val dailyCapBytes: Long = 200L * 1024 * 1024,
        /** 只在 Wi-Fi 下上传（采集照常入队，等 Wi-Fi 再传） */
        val wifiOnly: Boolean = true,
        val skipDuplicates: Boolean = true
    )

    data class Usage(
        val todayCount: Int = 0,
        val todayBytes: Long = 0,
        val lastCaptureAtMs: Long = 0,
        /** 最近若干帧的 dHash，用于近似去重 */
        val recentHashes: List<Long> = emptyList()
    )

    sealed interface Decision {
        /** 采样这一帧入队 */
        data object Capture : Decision
        /** 跳过，reason 用于日志与界面解释（不要静默跳过） */
        data class Skip(val reason: String) : Decision
    }

    /** 该不该把这一帧收下来 */
    fun shouldCapture(rules: Rules, usage: Usage, nowMs: Long, frameHash: Long): Decision {
        if (!rules.enabled) return Decision.Skip("采集开关未打开")
        if (usage.todayCount >= rules.dailyCapCount) return Decision.Skip("已达今日张数上限")
        if (usage.todayBytes >= rules.dailyCapBytes) return Decision.Skip("已达今日流量上限")
        val interval = rules.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        if (usage.lastCaptureAtMs > 0 && nowMs - usage.lastCaptureAtMs < interval) {
            return Decision.Skip("间隔未到")
        }
        if (rules.skipDuplicates && usage.recentHashes.any { hamming(it, frameHash) <= DUP_HAMMING_THRESHOLD }) {
            return Decision.Skip("与最近画面几乎相同")
        }
        return Decision.Capture
    }

    /** 现在能不能把队里的东西发出去 */
    fun shouldUploadNow(rules: Rules, onWifi: Boolean): Decision {
        if (!rules.enabled) return Decision.Skip("采集开关未打开")
        if (rules.wifiOnly && !onWifi) return Decision.Skip("设置了仅 Wi-Fi 上传")
        return Decision.Capture
    }

    /** 64 位差异哈希：逐位比较相邻像素亮度，抗轻微亮度/压缩差异 */
    fun dHash(luma: IntArray, width: Int, height: Int): Long {
        if (width < 2 || height <= 0 || luma.size < width * height) return 0L
        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                if (bit >= 64) return hash
                if (luma[y * width + x] > luma[y * width + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** 汉明距离（两个 dHash 有多少位不同） */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 把 hash 列表裁剪到最近 [keep] 个（避免偏好里无限增长） */
    fun trimHashes(hashes: List<Long>, keep: Int = 8): List<Long> =
        if (hashes.size <= keep) hashes else hashes.takeLast(keep)
}
