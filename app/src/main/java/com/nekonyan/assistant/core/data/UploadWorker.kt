package com.nekonyan.assistant.core.data

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nekonyan.assistant.core.net.RequestSigner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 样本批量上传（WorkManager）
 *
 * ── 相对旧实现的修正 ────────────────────────────────────────
 * 旧实现：HTTP 200 且 body 里 {"code":400,"rejects":[...]} 时**无法识别**，
 *        一律走 Result.retry() → 不合规样本每次重传、每次被拒，永远出不了队。
 * 新实现：解析响应体的 code / rejects，逐条处理：
 *        · code == 0            → 整批成功，本地删除
 *        · code == 400          → 服务端判定不合规：**按 id 精确丢弃**，不重试
 *        · 其他 code / 网络错误 → 保留，指数退避重试
 *        · 服务端返回的关键字（如 code == 401 鉴权失败）不消耗重试次数，直接失败
 *
 * 签名规范见 RequestSigner（与服务端 model_dist_server.py 逐字节一致）。
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val TAG = "yolo-sample-upload"
        private const val LOG_TAG = "UploadWorker"
        private const val MAX_TRY = 5                 // 超过则丢弃，避免卡死队列
        private const val MAX_BATCH = 20
        private const val PATH_UPLOAD = "/data/upload"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val base = inputData.getString("base")?.trimEnd('/') ?: return Result.failure()
        val token = inputData.getString("token") ?: return Result.failure()
        val secret = inputData.getString("secret") ?: return Result.failure()
        val ctx = applicationContext
        val url = base + PATH_UPLOAD

        val pending = DataCollector.SampleStore.pending(ctx, MAX_BATCH)
        if (pending.isEmpty()) return Result.success()

        return try {
            val payload = buildPayload(pending)
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload", payload)

            var attached = 0
            pending.forEach { s ->
                val f = File(s.imagePath)
                if (f.exists() && f.length() > 0) {
                    mp.addFormDataPart("files", "${s.id}.jpg", f.asRequestBody(JPEG))
                    attached++
                } else {
                    Log.w(LOG_TAG, "本地文件缺失，丢弃索引: ${s.id}")
                }
            }
            if (attached == 0) {
                // 只剩索引没有文件 → 清理，避免无限重试
                DataCollector.SampleStore.removeAll(ctx, pending.map { it.id })
                return Result.success()
            }

            val builder = Request.Builder().url(url).post(mp.build())
            // ★ 只签 payload 字段原文（multipart boundary 随机，无法对整个 body 复现哈希）
            RequestSigner.signInto(builder, "POST", url, token, secret, signedBody = payload)

            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "响应不是 JSON（HTTP ${resp.code}）: ${text.take(200)}")
                    null
                }

                // 鉴权/签名类错误：重试没有意义，也不该消耗样本
                if (resp.code == 401 || json?.optInt("code", -1) == 401) {
                    Log.e(LOG_TAG, "鉴权失败，停止上传（检查 Token / 签名实现是否两端一致）")
                    return Result.failure()
                }

                val code = json?.optInt("code", -1) ?: -1
                when {
                    resp.isSuccessful && code == 0 -> {
                        DataCollector.SampleStore.removeAll(ctx, pending.map { it.id })
                        pending.forEach { File(it.imagePath).takeIf { f -> f.exists() }?.delete() }
                        Log.i(LOG_TAG, "上传成功 ${pending.size} 条，已清理本地文件")
                        Result.success()
                    }

                    code == 400 -> {
                        // ★ 关键修正：服务端判定不合规的样本，按 id 精确丢弃，绝不重传
                        val rejected = json.optJSONArray("rejects") ?: JSONArray()
                        val sentIds = pending.map { it.id }.toHashSet()
                        val hardReject = HashSet<String>()
                        for (i in 0 until rejected.length()) {
                            val o = rejected.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            if (id.isNotEmpty() && id in sentIds) hardReject.add(id)
                            Log.w(LOG_TAG, "样本被拒: $id (${o.optString("reason")})")
                        }
                        if (hardReject.isEmpty()) {
                            Log.w(LOG_TAG, "服务端返回 400 但未给出 rejects 明细，保守丢弃整批")
                            hardReject.addAll(sentIds)
                        }
                        DataCollector.SampleStore.removeAll(ctx, hardReject)
                        hardReject.forEach { id ->
                            pending.firstOrNull { it.id == id }?.let {
                                File(it.imagePath).takeIf { f -> f.exists() }?.delete()
                            }
                        }
                        Result.success()
                    }

                    resp.code >= 500 || resp.code == 429 || !resp.isSuccessful -> {
                        Result.retry()
                    }

                    else -> {
                        Log.w(LOG_TAG, "未知响应 HTTP ${resp.code} code=$code")
                        Result.retry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "上传失败: ${e.message}")
            try {
                // 失败计数 + 淘汰超过上限的样本，防止队列卡死
                DataCollector.SampleStore.incTry(ctx, pending.map { it.id })
                val giveUp = DataCollector.SampleStore.all(ctx).filter { it.tryCount >= MAX_TRY }
                if (giveUp.isNotEmpty()) {
                    Log.w(LOG_TAG, "重试超过 $MAX_TRY 次，丢弃 ${giveUp.size} 条")
                    DataCollector.SampleStore.removeAll(ctx, giveUp.map { it.id })
                }
            } catch (inner: Exception) {
                Log.w(LOG_TAG, "失败清理异常: ${inner.message}")
            }
            Result.retry()
        }
    }

    /** 构造 payload（与 docs/01 数据契约一致；★ 补上了旧实现遗漏的 device_id） */
    private fun buildPayload(batch: List<DataCollector.SampleRecord>): String {
        val batchId = "b-" + UUID.randomUUID().toString().substring(0, 8)
        val obj = JSONObject().apply {
            put("batch_id", batchId)
            put("device_id", DataCollector.deviceIdHash)
            put("app_version", appVersionName())
            put("model_version", batch.first().modelVersion)
            put("privacy", JSONObject().apply {
                put("blur_face", DataCollector.config.blurFaces)
                put("blur_plate", DataCollector.config.blurFaces)
                put("original_image", false)
            })
            put("items", JSONArray().apply {
                batch.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("type", s.type)
                        put("image_kind", "crop")
                        put("bbox", s.bbox)
                        put("cls", s.cls)
                        put("conf", s.conf.toDouble())
                        s.userLabel?.let { put("user_label", it) }
                        put("captured_at", s.capturedAt)
                    })
                }
            })
        }
        return obj.toString()
    }

    private fun appVersionName(): String = try {
        val pm = applicationContext.packageManager
        pm.getPackageInfo(applicationContext.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    private val JPEG = "image/jpeg".toMediaType()
}
