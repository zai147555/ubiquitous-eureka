package com.nekonyan.assistant.core.net

import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.log.Redactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务返回的一条检测结果（坐标为**原图像素系**，接入指南第 130 行明确过） */
data class YoloDetection(
    val cls: Int,
    val conf: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float
)

data class YoloServiceInfo(
    val service: String,
    val model: String,
    val imgsz: Int,
    val confThreshold: Float,
    val maxConcurrency: Int
)

data class YoloDetectResult(
    val count: Int,
    val detections: List<YoloDetection>,
    val costMs: Double,
    val totalMs: Double,
    val imageWidth: Int,
    val imageHeight: Int
)

sealed interface ServiceOutcome<out T> {
    data class Ok<T>(val value: T) : ServiceOutcome<T>
    data class Err(val message: String) : ServiceOutcome<Nothing>
}

/**
 * 「模型/」里那套 YOLO11n 检测服务的客户端（`模型/接入指南.md`）。
 *
 * 按接入指南的三条硬约束实现：
 *   ① 认证头是 `X-API-Token`；`GET /` 免认证（用来判断"服务活着吗"）；
 *   ② `POST /detect` 是 multipart，字段名 **file**；支持 jpg/png/webp/bmp；
 *   ③ 并发上限 2、单张 90~200ms → 超时给到 30 秒、失败重试交给调用方而不是无脑重发。
 *
 * 与 DeepSeek 客户端的区别：这里是**局域网自建服务**，接入指南给的示例就是 `http://x.x.x.x:8000`，
 * 因此不能像云端那样强制 https（强制的后果是根本连不上）；Token 仍然只进 Keystore、只进日志脱敏后的字段。
 */
class YoloServiceClient(private val baseClient: OkHttpClient = defaultClient()) {

    /** GET /（免认证）：确认服务在跑、拿到模型名与阈值 */
    fun info(baseUrl: String): ServiceOutcome<YoloServiceInfo> =
        request(baseUrl, "/", token = null) { body ->
            val data = JSONObject(body).optJSONObject("data") ?: return@request null
            YoloServiceInfo(
                service = data.optString("service", "未知服务"),
                model = data.optString("model", "未知模型"),
                imgsz = data.optInt("imgsz", 640),
                confThreshold = data.optDouble("conf_threshold", 0.3).toFloat(),
                maxConcurrency = data.optInt("max_concurrency", 1)
            )
        }

    /** GET /health（需认证）：返回人话状态；busy_reject > 0 说明并发被打满过 */
    fun health(baseUrl: String, token: String): ServiceOutcome<String> =
        request(baseUrl, "/health", token) { body ->
            val data = JSONObject(body).optJSONObject("data") ?: return@request null
            val stats = data.optJSONObject("stats")
            val total = stats?.optInt("total", 0) ?: 0
            val busy = stats?.optInt("busy_reject", 0) ?: 0
            buildString {
                append("服务正常 · 模型已加载=").append(data.optBoolean("model_loaded", false))
                append(" · 累计 ").append(total).append(" 次")
                if (busy > 0) append(" · 并发打满过 ").append(busy).append(" 次（调用太密了）")
            }
        }

    /** POST /detect（需认证）：传一张图片的字节，拿回原图坐标系的检测框 */
    fun detect(baseUrl: String, token: String, imageBytes: ByteArray, fileName: String = "frame.jpg"):
        ServiceOutcome<YoloDetectResult> {
        val url = endpoint(baseUrl, "/detect")
            ?: return ServiceOutcome.Err("服务地址不合法：$baseUrl")
        if (token.isBlank()) return ServiceOutcome.Err("还没有填 API Token（服务端 X-API-Token）")

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, imageBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("X-API-Token", token)
            .post(multipart)
            .build()

        return try {
            client().newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ServiceOutcome.Err(httpHint(resp.code, text))
                }
                val json = JSONObject(text)
                if (json.optInt("code", 0) != 0) {
                    return ServiceOutcome.Err("服务返回失败：" + json.optString("msg", "未知原因"))
                }
                val data = json.optJSONObject("data") ?: return ServiceOutcome.Err("响应缺少 data 字段")
                val arr = data.optJSONArray("detections")
                val dets = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) {
                        val d = arr!!.optJSONObject(i) ?: continue
                        add(
                            YoloDetection(
                                cls = d.optInt("cls", -1),
                                conf = d.optDouble("conf", 0.0).toFloat(),
                                x1 = d.optDouble("x1", 0.0).toFloat(),
                                y1 = d.optDouble("y1", 0.0).toFloat(),
                                x2 = d.optDouble("x2", 0.0).toFloat(),
                                y2 = d.optDouble("y2", 0.0).toFloat()
                            )
                        )
                    }
                }
                val image = data.optJSONObject("image")
                ServiceOutcome.Ok(
                    YoloDetectResult(
                        count = data.optInt("count", dets.size),
                        detections = dets,
                        costMs = data.optDouble("cost_ms", 0.0),
                        totalMs = data.optDouble("total_ms", 0.0),
                        imageWidth = image?.optInt("width", 0) ?: 0,
                        imageHeight = image?.optInt("height", 0) ?: 0
                    )
                )
            }
        } catch (e: IOException) {
            NekoLog.warn(NekoLog.MODULE_NET, "yolo_service_io", e.javaClass.simpleName + ": " + e.message)
            ServiceOutcome.Err(networkHint(e))
        } catch (e: Exception) {
            ServiceOutcome.Err("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- 内部 ----------------

    private fun <T> request(
        baseUrl: String,
        path: String,
        token: String?,
        parse: (String) -> T?
    ): ServiceOutcome<T> {
        val url = endpoint(baseUrl, path) ?: return ServiceOutcome.Err("服务地址不合法：$baseUrl")
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrBlank()) builder.header("X-API-Token", token)
        return try {
            client().newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return ServiceOutcome.Err(httpHint(resp.code, text))
                val json = JSONObject(text)
                if (json.optInt("code", 0) != 0) {
                    return ServiceOutcome.Err("服务返回失败：" + json.optString("msg", "未知原因"))
                }
                parse(text)?.let { ServiceOutcome.Ok(it) }
                    ?: ServiceOutcome.Err("响应结构不符合接入指南（缺少 data 字段）")
            }
        } catch (e: IOException) {
            ServiceOutcome.Err(networkHint(e))
        } catch (e: Exception) {
            ServiceOutcome.Err("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun client(): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // 接入指南第 378 行：超时建议 30 秒
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 允许 http（局域网自建服务）；只做最基本的合法性检查 */
    private fun endpoint(baseUrl: String, path: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return base + path
    }

    private fun httpHint(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("msg", "") }.getOrDefault("")
        val head = when (code) {
            401, 403 -> "Token 不对或被拒绝（$code）"
            404 -> "路径不存在（404）：确认地址是 http://主机:8000"
            413 -> "图片太大（413）：接入指南建议压到 5MB 内"
            in 500..599 -> "服务端错误（$code）"
            else -> "请求失败（$code）"
        }
        return if (msg.isBlank()) head else "$head：${Redactor.redact(msg).take(120)}"
    }

    private fun networkHint(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "找不到主机：检查地址（局域网 IP 要能互通）"
        is java.net.SocketTimeoutException -> "超时（30 秒）：服务可能在排队（并发上限 2）"
        else -> "网络异常：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
}
