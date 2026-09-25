package com.nekonyan.assistant.core.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.security.BuiltinSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 训练数据采集：把采样到的画面**排队**在本地，再由用户（或采集模式）上传到自建服务。
 *
 * 三条设计底线（对应"自动上传"这件事最容易被做歪的地方）：
 *   ① **默认关闭**，且开关状态在界面上始终可见（采集时抓屏通知里也会显示计数）；
 *   ② **先入队、再上传**：断网/隧道挂掉（比如现在的 530）不会丢样本，恢复后自动补传；
 *   ③ **文件名就是内容哈希** —— 同一张图不可能入队两次，去重不靠额外状态表。
 *
 * 计数（今日张数/字节、上次采样时间、最近帧哈希）存在普通 prefs 里，
 * 按天自动归零；不引入新的数据库表（那会触发破坏性迁移，见 DbUpgradeGuard）。
 */
class TrainingCollector(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nekonyan_collect", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------------- 规则 ----------------

    fun rules(): CollectPolicy.Rules = CollectPolicy.Rules(
        enabled = prefs.getBoolean(K_ENABLED, false),
        intervalMs = prefs.getLong(K_INTERVAL, 30_000L),
        dailyCapCount = prefs.getInt(K_CAP_COUNT, 500),
        dailyCapBytes = prefs.getLong(K_CAP_BYTES, 200L * 1024 * 1024),
        wifiOnly = prefs.getBoolean(K_WIFI_ONLY, true),
        skipDuplicates = prefs.getBoolean(K_SKIP_DUP, true)
    )

    fun saveRules(r: CollectPolicy.Rules) = prefs.edit()
        .putBoolean(K_ENABLED, r.enabled)
        .putLong(K_INTERVAL, r.intervalMs)
        .putInt(K_CAP_COUNT, r.dailyCapCount)
        .putLong(K_CAP_BYTES, r.dailyCapBytes)
        .putBoolean(K_WIFI_ONLY, r.wifiOnly)
        .putBoolean(K_SKIP_DUP, r.skipDuplicates)
        .apply()

    // ---------------- 用量（按天归零）----------------

    fun usage(): CollectPolicy.Usage {
        rolloverIfNeeded()
        return CollectPolicy.Usage(
            todayCount = prefs.getInt(K_TODAY_COUNT, 0),
            todayBytes = prefs.getLong(K_TODAY_BYTES, 0),
            lastCaptureAtMs = prefs.getLong(K_LAST_AT, 0),
            recentHashes = prefs.getString(K_HASHES, "").orEmpty()
                .split(',').mapNotNull { it.toLongOrNull() }
        )
    }

    private fun rolloverIfNeeded() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (prefs.getString(K_DAY, "") != today) {
            prefs.edit()
                .putString(K_DAY, today)
                .putInt(K_TODAY_COUNT, 0)
                .putLong(K_TODAY_BYTES, 0)
                .apply()
        }
    }

    // ---------------- 入队 ----------------

    /**
     * 把一帧存进待传队列。
     * @return 是否真的入队（去重命中或写入失败都为 false）
     */
    fun enqueue(jpeg: ByteArray, frameHash: Long, source: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val u = usage()
        if (CollectPolicy.shouldCapture(rules(), u, nowMs, frameHash) !is CollectPolicy.Decision.Capture) return false
        val name = "%016x_%d_%s.jpg".format(frameHash, nowMs, source)
        return runCatching {
            File(dir, name).writeBytes(jpeg)
            prefs.edit()
                .putInt(K_TODAY_COUNT, u.todayCount + 1)
                .putLong(K_TODAY_BYTES, u.todayBytes + jpeg.size)
                .putLong(K_LAST_AT, nowMs)
                .putString(
                    K_HASHES,
                    CollectPolicy.trimHashes(u.recentHashes + frameHash).joinToString(",")
                )
                .apply()
            NekoLog.info(NekoLog.MODULE_PROJECTION, "collect_enqueued", "$name（${jpeg.size / 1024}KB，今日 ${u.todayCount + 1} 张）")
            true
        }.getOrElse {
            NekoLog.error(NekoLog.MODULE_PROJECTION, "collect_enqueue_failed", it.javaClass.simpleName)
            false
        }
    }

    /** 待传队列：张数与总字节（界面要显示，用户随时知道"还没传出去多少"） */
    fun pending(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    fun clearPending(): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        NekoLog.warn(NekoLog.MODULE_PROJECTION, "collect_cleared", "清空待传队列 $n 张")
        return n
    }

    // ---------------- 上传 ----------------

    data class UploadResult(val ok: Int, val failed: Int, val message: String)

    /**
     * 把队列里最旧的几张传上去。
     *
     * 地址与令牌**复用内置凭据**（与检测服务同一套）：不额外引入配置项，
     * 服务端只要在同一个 base 上实现 `POST /collect` 即可（见 docs/训练数据上传接口.md）。
     */
    suspend fun uploadPending(maxPerRun: Int = 5): UploadResult = withContext(Dispatchers.IO) {
        val rules = rules()
        val wifi = onWifi()
        val gate = CollectPolicy.shouldUploadNow(rules, wifi)
        // withContext 的 lambda 不是 inline —— 裸 return 非法（CI 直接报 'return' is prohibited here）
        if (gate is CollectPolicy.Decision.Skip) return@withContext UploadResult(0, 0, gate.reason)

        val creds = BuiltinSecretStore.load(context)
            ?: return@withContext UploadResult(0, 0, "内置凭据里没有服务地址，无法上传")
        val (base, token) = creds
        val url = base.trimEnd('/') + "/collect"

        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?: return@withContext UploadResult(0, 0, "队列为空"))
            .sortedBy { it.name }
            .take(maxPerRun)
        if (files.isEmpty()) return@withContext UploadResult(0, 0, "队列为空")

        var ok = 0
        var failed = 0
        var lastCode = 0
        for (f in files) {
            val r = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("source", f.name.substringAfterLast('_').removeSuffix(".jpg"))
                    .addFormDataPart("image", f.name, f.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                val req = Request.Builder().url(url).header("X-API-Token", token).post(body).build()
                http.newCall(req).execute().use { resp -> resp.isSuccessful to resp.code }
            }.getOrElse { false to -1 }
            if (r.first) {
                f.delete(); ok++
            } else {
                failed++
                lastCode = r.second
                NekoLog.warn(NekoLog.MODULE_PROJECTION, "collect_upload_failed", "${f.name} → HTTP ${r.second}")
                break   // 一张失败就停：多半是网络/服务问题，硬传只会刷屏
            }
        }
        val msg = if (failed == 0) "已上传 $ok 张" else "上传 $ok 张后失败（HTTP $lastCode）"
        NekoLog.info(NekoLog.MODULE_PROJECTION, "collect_upload", msg)
        UploadResult(ok, failed, msg)
    }

    /** 是否在 Wi-Fi 上（"仅 Wi-Fi 上传"靠它；拿不到状态时按"不是 Wi-Fi"处理，宁可不传） */
    fun onWifi(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private companion object {
        const val DIR = "collect_pending"
        const val K_ENABLED = "enabled"
        const val K_INTERVAL = "interval_ms"
        const val K_CAP_COUNT = "cap_count"
        const val K_CAP_BYTES = "cap_bytes"
        const val K_WIFI_ONLY = "wifi_only"
        const val K_SKIP_DUP = "skip_dup"
        const val K_TODAY_COUNT = "today_count"
        const val K_TODAY_BYTES = "today_bytes"
        const val K_LAST_AT = "last_at"
        const val K_HASHES = "recent_hashes"
        const val K_DAY = "day"
    }
}
