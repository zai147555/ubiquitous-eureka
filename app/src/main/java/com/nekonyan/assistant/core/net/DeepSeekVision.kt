package com.nekonyan.assistant.core.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 云端图片识别（DeepSeek 的 OpenAI 兼容视觉接口）。
 *
 * 契约来自官方文档 https://api-docs.deepseek.com/zh-cn/guides/vision/ ：
 *   · 支持图片的是 `deepseek-flash`（旧名 deepseek-v4-flash-vision-exp 已下线）；
 *   · `content` 从字符串变成**块数组**：`{type:text}` + `{type:image_url,image_url:{url:data:...}}`；
 *   · **图片只能出现在 `user` 消息里**（system/assistant 带图会 400）；
 *   · 单张内联图片上限 32MiB、请求体 48MiB、每张图最多约 1024 token（所以发截图很便宜）。
 *
 * 为什么手机端还要先压图：截图 1080×2400 直接 base64 会到几 MB，
 * 而模型进模型前本来就会缩到约 1300×1300 —— 先压等于省流量、不改结果。
 */
object DeepSeekVision {

    /** 官方支持图片的模型名 */
    const val VISION_MODEL = "deepseek-flash"

    /** 文本专用模型（配了这些就自动改用 [VISION_MODEL]，否则会 400） */
    private val TEXT_ONLY = setOf("deepseek-chat", "deepseek-reasoner", "", "deepseek-v3", "deepseek-v3.1")

    /** 单张内联上限 32MiB，我们压到远小于它；这里只做最后一道保护 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Err(val message: String) : Result
    }

    /** 用哪条链路取决于用户配的模型：配了文本模型就自动换视觉模型，避免"开了开关却一直 400" */
    fun modelFor(config: ChatConfig): String =
        if (config.model.trim().lowercase() in TEXT_ONLY) VISION_MODEL else config.model.trim()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun describe(
        config: ChatConfig,
        jpeg: ByteArray,
        prompt: String = "请用中文简要描述这张图片的内容；如果图里有文字，把关键文字读出来。",
        shrink: Boolean = true
    ): Result = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext Result.Err("还没配 API Key")
        val bytes = if (shrink) shrinkForVision(jpeg) else jpeg
        if (bytes.size > MAX_BYTES) return@withContext Result.Err("图片太大（${bytes.size / 1024 / 1024}MB）")

        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        val body = JSONObject()
            .put("model", modelFor(config))
            .put("temperature", 0.2)
            .put("max_tokens", 800)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
            .toString()

        runCatching {
            val req = Request.Builder()
                .url(config.endpoint())
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                    }.getOrDefault("")
                    NekoLog.warn(NekoLog.MODULE_NET, "vision_http_error", "HTTP ${resp.code} ${msg.take(120)}")
                    Result.Err(
                        when (resp.code) {
                            401, 403 -> "API Key 被拒绝（${resp.code}）"
                            400 -> "请求被拒（400）：模型可能不支持图片 —— ${msg.take(80)}"
                            429 -> "触发限流（429），稍后再试"
                            else -> "服务端错误（${resp.code}）${if (msg.isNotBlank()) "：${msg.take(80)}" else ""}"
                        }
                    )
                } else {
                    val out = runCatching {
                        JSONObject(text).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content")
                    }.getOrNull().orEmpty()
                    if (out.isBlank()) Result.Err("模型没有返回内容") else Result.Ok(out)
                }
            }
        }.getOrElse { e ->
            NekoLog.warn(NekoLog.MODULE_NET, "vision_failed", e.javaClass.simpleName + ": " + e.message?.take(80))
            Result.Err("网络异常：${e.javaClass.simpleName}")
        }
    }

    /** 长边压到 1600（模型反正会缩到约 1300×1300，先压省流量且不改结果） */
    fun shrinkForVision(raw: ByteArray, maxSide: Int = 1600): ByteArray = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return raw
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return raw
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        bos.toByteArray().takeIf { it.isNotEmpty() } ?: raw
    }.getOrDefault(raw)
}

/** 「用 DS 云端识别图片」开关（普通 prefs：不是密钥） */
object VisionSettings {
    private const val PREFS = "nekonyan_vision"
    private const val KEY = "cloud_vision"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
}
