package com.nekonyan.assistant.core.voice

import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RVC 语音转换服务客户端（daswer123/rvc-python 的 `rvc_python/api.py`）。
 *
 * 契约读自源码，不靠印象：
 *   · `POST /convert_file` —— multipart，字段名 **file**，响应体就是 `audio/wav` 二进制；
 *     （另一个 `POST /convert` 收的是 JSON，手机端用不上）
 *   · `GET /models` → `{"models":[...]}`；`POST /models/{name}` 加载音色；
 *   · `GET /params` / `POST /params` 调整 pitch、f0method 等。
 *
 * ★ 全部方法都是 suspend + withContext(IO)：这是"阻塞网络绝不上主线程"的硬约束
 *   （本项目在这一点上真栽过 —— NetworkOnMainThreadException）。
 */
class RvcClient(
    private val baseUrl: String,
    private val token: String = "",
    private val http: OkHttpClient = default()
) {

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun req(path: String): Request.Builder =
        Request.Builder().url(url(path)).apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }

    /** 列出可用音色模型 */
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(req("/models").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
        }.getOrElse {
            NekoLog.warn(NekoLog.MODULE_AI, "rvc_models_failed", it.javaClass.simpleName)
            emptyList()
        }
    }

    /** 切换当前音色 */
    suspend fun loadModel(name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(req("/models/$name").post(ByteArray(0).toRequestBody(null)).build())
                .execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    /**
     * 转换一段 WAV（TTS 的产物）→ 目标音色 WAV。
     * 失败返回 null 并留日志，由上层决定"退回原始 TTS 音频"还是报错。
     */
    suspend fun convert(wav: ByteArray, fileName: String = "tts.wav"): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            http.newCall(req("/convert_file").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) {
                    NekoLog.warn(NekoLog.MODULE_AI, "rvc_convert_http", "code=${r.code}")
                    return@withContext null
                }
                val bytes = r.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    NekoLog.warn(NekoLog.MODULE_AI, "rvc_convert_empty", "服务返回空音频")
                    null
                } else {
                    NekoLog.info(NekoLog.MODULE_AI, "rvc_convert_ok", "${bytes.size / 1024}KB")
                    bytes
                }
            }
        }.getOrElse {
            NekoLog.warn(NekoLog.MODULE_AI, "rvc_convert_failed", it.javaClass.simpleName + ": " + it.message)
            null
        }
    }

    companion object {
        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // RVC 在 CPU 上慢，超时给足
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
