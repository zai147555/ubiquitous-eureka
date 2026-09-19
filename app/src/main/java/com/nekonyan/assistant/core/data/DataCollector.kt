package com.nekonyan.assistant.core.data

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.work.*
import com.nekonyan.assistant.core.net.RequestSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据采集 + 脱敏（闭环的数据回流端）
 *
 * 设计原则（与 docs/03 对齐）：
 *   · 默认只传「裁剪区域」，不传原图
 *   · 人脸/车牌默认模糊 —— ★ 由 faceDetector / 兜底区域策略真实执行
 *   · 采样有配额上限，超出淘汰最旧
 *   · 用户可一键关闭（config.enabled = false）
 *
 * 本文件只负责「采集 + 落盘 + 元数据存储」；上传在 UploadWorker.kt。
 *
 * ⚠️ 存储层说明：SampleStore 用 SharedPreferences + JSON 仅为可独立运行的示例，
 *    生产环境应换 Room（docs/03 的 SampleEntity）。替换时保持 add/all/remove/markUploaded
 *    四个方法的语义即可，其余代码无需改动。
 */
object DataCollector {

    private const val TAG = "DataCollector"
    private const val DIR_SAMPLES = "samples"
    private const val MAX_DIR_MB = 200L
    private const val MAX_SAMPLE_BYTES = 512 * 1024

    /** 采样配置 */
    data class Config(
        var enabled: Boolean = true,            // 用户可关闭（隐私）
        var lowConfMin: Float = 0.15f,          // 低置信度区间
        var lowConfMax: Float = 0.35f,
        var sampleEveryN: Int = 100,            // 定期抽样：每 N 次取 1（0/负数 = 关闭抽样）
        var cropPadding: Float = 0.15f,
        var jpegQuality: Int = 80,
        var blurFaces: Boolean = true,
        var uploadWifiOnly: Boolean = true,
        /** 每批上传条数（服务端与内存占用都按这个规模设计） */
        var uploadBatchSize: Int = 20
    )

    @Volatile var config = Config()

    private val counter = AtomicInteger(0)

    private var appVersion: String = "0.0.0"

    /**
     * 人脸框提供者（可选注入 ML Kit / 已有检测结果）。
     * 返回空列表时会走「人形框上部兜底打码」，不会出现「声明脱敏但实际没脱敏」。
     */
    @Volatile var faceDetector: ((Bitmap) -> List<Rect>)? = null

    /** 设备指纹哈希（docs/01 数据契约要求随样本上报，用于「删除我上传的数据」） */
    @Volatile var deviceIdHash: String = "unknown"

    fun init(context: Context, appVersion: String) {
        this.appVersion = appVersion
        if (deviceIdHash == "unknown") {
            deviceIdHash = DeviceId.hash(context)
        }
    }

    private fun sampleDir(ctx: Context) = File(ctx.filesDir, DIR_SAMPLES).apply { mkdirs() }

    // ============================================================
    // ① 采集入口：推理结束后调用
    // ============================================================

    /**
     * @param source 原始位图（**不会被修改**）
     * @param dets   本次检测结果
     * @param userCorrected 用户纠正 (框, 正确类别)，可空
     */
    fun onInference(
        ctx: Context,
        source: Bitmap,
        dets: List<com.nekonyan.assistant.core.yolo.NcnnDetector.Detection>,
        userCorrected: Pair<RectF, Int>? = null,
        modelVersion: String = "unknown"
    ) {
        val cfg = config
        if (!cfg.enabled) return
        val n = counter.incrementAndGet()

        val lowest = dets.minByOrNull { it.conf }
        val type: String = when {
            userCorrected != null -> "user_corrected"
            dets.any { it.conf in cfg.lowConfMin..cfg.lowConfMax } -> "low_conf"
            cfg.sampleEveryN > 0 && n % cfg.sampleEveryN == 0 -> "sampled"
            else -> return                                   // 无价值，不采集
        }

        try {
            val target = userCorrected?.first
                ?: lowest?.let { RectF(it.x1, it.y1, it.x2, it.y2) }
                ?: return

            val crop = cropWithPadding(source, target, cfg.cropPadding) ?: return
            val masked = if (cfg.blurFaces) blurSensitiveRegions(crop, target) else crop
            val bytes = encodeJpeg(masked, cfg.jpegQuality)
            if (bytes.size > MAX_SAMPLE_BYTES) {             // 太大丢弃
                Log.d(TAG, "样本过大丢弃: ${bytes.size}B")
                return
            }

            val id = "s-" + UUID.randomUUID().toString().substring(0, 8)
            val f = File(sampleDir(ctx), "$id.jpg")
            f.writeBytes(bytes)

            SampleStore.add(ctx, SampleRecord(
                id = id, type = type, imagePath = f.absolutePath,
                bbox = "${target.left},${target.top},${target.right},${target.bottom}",
                cls = userCorrected?.second ?: lowest?.cls ?: -1,
                conf = lowest?.conf ?: 0f,
                userLabel = userCorrected?.second,
                capturedAt = System.currentTimeMillis(),
                modelVersion = modelVersion
            ))
            enforceQuota(ctx)
            Log.d(TAG, "采样入队: $id ($type)")
        } catch (e: Exception) {
            Log.w(TAG, "采集失败: ${e.message}")
        }
    }

    // ============================================================
    // ② 脱敏
    // ============================================================

    /**
     * 裁剪目标区域。
     * ★ 修正：对坐标做**排序 + 夹紧 + 越界校验**，框非法时返回 null 而不是抛
     *   IllegalArgumentException（用户手画的框最容易出现 right<left）。
     */
    private fun cropWithPadding(src: Bitmap, r: RectF, pad: Float): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 1 || h <= 1) return null

        var left = minOf(r.left, r.right)
        var top = minOf(r.top, r.bottom)
        var right = maxOf(r.left, r.right)
        var bottom = maxOf(r.top, r.bottom)

        val pw = (right - left) * pad
        val ph = (bottom - top) * pad
        left -= pw; top -= ph; right += pw; bottom += ph

        val l = left.coerceIn(0f, (w - 1).toFloat()).toInt()
        val t = top.coerceIn(0f, (h - 1).toFloat()).toInt()
        val rr = right.coerceIn(0f, w.toFloat()).toInt()
        val b = bottom.coerceIn(0f, h.toFloat()).toInt()

        val cw = (rr - l).coerceAtMost(w - l)
        val ch = (b - t).coerceAtMost(h - t)
        if (cw < 8 || ch < 8) {                     // 退化框（点/细线）没有训练价值
            Log.d(TAG, "框过小，跳过: ${cw}x$ch")
            return null
        }
        return try {
            Bitmap.createBitmap(src, l, t, cw, ch)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "裁剪失败($l,$t,$cw,$ch / ${w}x$h): ${e.message}")
            null
        }
    }

    /**
     * 对「人脸/车牌」区域打码。
     *
     * ★ 修正原实现的两个问题：
     *   1) 原实现 detectFacesIfAvailable() 恒返回 emptyList()，等于「声明已脱敏但实际没脱敏」；
     *   2) 原实现用 BlurMaskFilter + drawRect，模糊的是所画矩形的边缘（画出模糊色块），
     *      并**不能模糊底下的像素** —— 那不是打码。
     *
     * 现在：优先用注入的 faceDetector；没注入则对「人形框的上部（头部带）」兜底打码，
     *      真正做到「宁可多打一点，也不漏」。
     * 打码算法用「缩小再放大」（像素化），在低端机上比高斯模糊快且不可逆。
     */
    private fun blurSensitiveRegions(bmp: Bitmap, target: RectF): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val regions = ArrayList<Rect>()

        faceDetector?.invoke(out)?.let { regions.addAll(it) }

        if (regions.isEmpty()) {
            // 目标框在人脸附近的最常见形态是「人/上半身」，取其上 1/3 作为头部带兜底
            val h = out.height
            val headBand = Rect(0, 0, out.width, (h * HEAD_BAND_RATIO).toInt().coerceAtLeast(8))
            regions.add(headBand)
        }
        return pixelate(out, regions)
    }

    /** 把指定区域像素化（缩小 → 放大），不可逆恢复 */
    private fun pixelate(src: Bitmap, regions: List<Rect>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
        }
        regions.forEach { r0 ->
            val r = Rect(r0)
            if (!r.intersect(0, 0, out.width, out.height)) return@forEach
            if (r.width() < 2 || r.height() < 2) return@forEach
            val block = PIXEL_BLOCK_PX
            val sw = (r.width() / block).coerceAtLeast(1)
            val sh = (r.height() / block).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(
                Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height()), sw, sh, false
            )
            canvas.drawBitmap(small, null, r, paint)
            small.recycle()
        }
        return out
    }

    private const val HEAD_BAND_RATIO = 0.35f   // 兜底：模糊裁剪图上方 35%（头部带）
    private const val PIXEL_BLOCK_PX = 12       // 像素化块大小（越大越糊）

    private fun encodeJpeg(bmp: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    // ============================================================
    // ③ 配额控制
    // ============================================================

    /** 目录超限则按 capturedAt 升序（优先 sampled）淘汰最旧 */
    private fun enforceQuota(ctx: Context) {
        try {
            val dir = sampleDir(ctx)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= MAX_DIR_MB * 1024 * 1024) return

            val records = SampleStore.all(ctx).associateBy { it.id }
            val victims = files.sortedWith(
                compareBy(
                    { records[it.nameWithoutExtension]?.type == "sampled" },  // sampled 优先淘汰
                    { records[it.nameWithoutExtension]?.capturedAt ?: it.lastModified() }
                )
            )
            for (f in victims) {
                if (totalBytes <= MAX_DIR_MB * 1024 * 1024) break
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                    SampleStore.remove(ctx, f.nameWithoutExtension)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "配额清理失败: ${e.message}")
        }
    }

    // ============================================================
    // ④ 排程上传（WorkManager）
    // ============================================================

    fun scheduleUpload(ctx: Context, apiBase: String, token: String, signSecret: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.uploadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val input = workDataOf(
            "base" to apiBase,
            "token" to token,
            "secret" to signSecret
        )
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInputData(input)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UploadWorker.TAG)
            .build()

        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(UploadWorker.TAG, ExistingWorkPolicy.KEEP, req)
    }

    // ============================================================
    // 内部数据存取（示例实现；生产换 Room，见文件头说明）
    // ============================================================

    internal data class SampleRecord(
        val id: String, val type: String, val imagePath: String,
        val bbox: String, val cls: Int, val conf: Float,
        val userLabel: Int?, val capturedAt: Long, val modelVersion: String,
        var uploaded: Boolean = false, var tryCount: Int = 0
    )

    internal object SampleStore {
        private const val PREF = "yolo_samples"

        /**
         * ★ 修正并发丢样本：原实现「读-改-写 + apply()」在推理线程与上传 Worker
         * 并发时会互相覆盖（apply 还是异步落盘）。这里用单锁串行 + commit()，
         * 保证「同一进程内不丢」；跨进程场景需换 Room/文件锁，见文件头说明。
         */
        private val lock = Any()

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        fun add(ctx: Context, r: SampleRecord) = lock.synchronized {
            val arr = allLocked(ctx).toMutableList()
            arr.add(r)
            saveLocked(ctx, arr)
        }

        fun all(ctx: Context): List<SampleRecord> = lock.synchronized { allLocked(ctx) }

        fun pending(ctx: Context, limit: Int): List<SampleRecord> =
            lock.synchronized { allLocked(ctx).filter { !it.uploaded }.take(limit) }

        fun remove(ctx: Context, id: String) = lock.synchronized {
            saveLocked(ctx, allLocked(ctx).filterNot { it.id == id })
        }

        fun removeAll(ctx: Context, ids: Collection<String>) = lock.synchronized {
            if (ids.isEmpty()) return@Synchronized
            val set = ids.toHashSet()
            saveLocked(ctx, allLocked(ctx).filterNot { it.id in set })
        }

        fun markUploaded(ctx: Context, ids: Collection<String>) = lock.synchronized {
            if (ids.isEmpty()) return@Synchronized
            val set = ids.toHashSet()
            val list = allLocked(ctx).map {
                if (it.id in set) it.copy(uploaded = true) else it
            }
            saveLocked(ctx, list)
        }

        fun incTry(ctx: Context, ids: Collection<String>) = lock.synchronized {
            if (ids.isEmpty()) return@Synchronized
            val set = ids.toHashSet()
            val list = allLocked(ctx).map {
                if (it.id in set) it.copy(tryCount = it.tryCount + 1) else it
            }
            saveLocked(ctx, list)
        }

        private fun allLocked(ctx: Context): List<SampleRecord> = try {
            val s = prefs(ctx).getString("list", "[]") ?: "[]"
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SampleRecord(
                    o.getString("id"), o.getString("type"), o.getString("image_path"),
                    o.getString("bbox"), o.getInt("cls"), o.getDouble("conf").toFloat(),
                    if (o.isNull("user_label")) null else o.getInt("user_label"),
                    o.getLong("captured_at"), o.optString("model_version", "unknown"),
                    o.optBoolean("uploaded"), o.optInt("try_count")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "样本索引损坏，按空处理: ${e.message}")
            emptyList()
        }

        private fun saveLocked(ctx: Context, list: List<SampleRecord>) {
            val arr = org.json.JSONArray()
            list.forEach { r ->
                arr.put(org.json.JSONObject().apply {
                    put("id", r.id); put("type", r.type); put("image_path", r.imagePath)
                    put("bbox", r.bbox); put("cls", r.cls); put("conf", r.conf.toDouble())
                    if (r.userLabel != null) put("user_label", r.userLabel)
                    put("captured_at", r.capturedAt); put("model_version", r.modelVersion)
                    put("uploaded", r.uploaded); put("try_count", r.tryCount)
                })
            }
            // commit() 同步落盘：采集是低频操作，换来确定性
            prefs(ctx).edit().putString("list", arr.toString()).commit()
        }
    }
}

/** 设备指纹哈希（不可逆；与账号解绑，见 docs/03 隐私合规） */
internal object DeviceId {
    fun hash(ctx: Context): String = try {
        val raw = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        // 加盐后哈希，避免 ANDROID_ID 直接可逆比对
        val salted = "yolo-sample-v1:" + raw + ":" + ctx.packageName
        RequestSigner.sha256Hex(salted.toByteArray(Charsets.UTF_8)).substring(0, 32)
    } catch (e: Exception) {
        "unknown"
    }
}
