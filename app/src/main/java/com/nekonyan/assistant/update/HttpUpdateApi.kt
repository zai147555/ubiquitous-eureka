package com.nekonyan.assistant.update

import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.net.RequestSigner
import com.nekonyan.assistant.core.util.SigningCanonical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 模型热更新服务的客户端实现（`UpdateApi`）。
 *
 * 契约完全照服务端「对接速查卡」与实测结果：
 *   · `GET /model/latest?app_version=&current=&device_id=` —— 三个 query 恰好是升序，
 *     与签名要求（按 (key,value) 升序拼接）一致；
 *   · 四个签名头：`X-API-Token` / `X-Timestamp`（**毫秒**）/ `X-Nonce`（16 位）/ `X-Signature`；
 *     canonical = `GET\n/model/latest\n<query>\n<ts>\n<nonce>\nsha256("")`，HMAC-SHA256(sign_secret)；
 *   · **下载同样需要签名**（只带 Token 会 401，这是实测出来的），
 *     下载那一步由 [ModelUpdater.downloadTo] 调 `signer.signInto` 完成，这里不重复实现；
 *   · 返回 null 表示「无可用更新」，网络/协议错误抛异常（由调用方兜底，两者含义不同）。
 *
 * 刻意不依赖 Retrofit（工程里没有这个依赖）：OkHttp + org.json 足够，少一层抽象也少一处出错点。
 */
class HttpUpdateApi(
    private val baseUrl: String,
    private val token: String,
    private val signSecret: String,
    private val http: OkHttpClient = defaultClient()
) : UpdateApi {

    override suspend fun fetchLatest(appVersion: String, current: String, deviceId: String): ModelMeta? =
        withContext(Dispatchers.IO) {
            val path = "/model/latest"
            // 顺序即升序：app_version < current < device_id
            val query = "app_version=${enc(appVersion)}&current=${enc(current)}&device_id=${enc(deviceId)}"
            val url = baseUrl.trimEnd('/') + path + "?" + query

            val builder = Request.Builder().url(url).get()
            val ts = System.currentTimeMillis().toString()
            val nonce = RequestSigner.newNonce()
            val bodyHash = RequestSigner.sha256Hex(ByteArray(0))
            val signature = RequestSigner.hmacSha256Hex(
                signSecret,
                SigningCanonical.canonical("GET", path, query, ts, nonce, bodyHash)
            )
            builder.header("X-API-Token", token)
                .header("X-Timestamp", ts)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)

            try {
                http.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        NekoLog.warn(NekoLog.MODULE_UPDATE, "update_http",
                            "code=${resp.code} body=${text.take(120)}")
                        throw IOException("更新服务返回 ${resp.code}：${text.take(120)}")
                    }
                    parse(text)
                }
            } catch (e: IOException) {
                NekoLog.warn(NekoLog.MODULE_UPDATE, "update_io", e.javaClass.simpleName + ": " + e.message)
                throw e
            }
        }

    private fun parse(text: String): ModelMeta? = runCatching {
        val o = JSONObject(text)
        if (!o.optBoolean("available", true)) return@runCatching null
        val arr = o.optJSONArray("files") ?: return@runCatching null
        val files = buildList {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                add(
                    ModelFile(
                        name = f.optString("name"),
                        size = f.optLong("size"),
                        sha256 = f.optString("sha256"),
                        url = f.optString("url")
                    )
                )
            }
        }
        if (files.isEmpty()) return@runCatching null
        val labels = buildList {
            val la = o.optJSONArray("labels")
            for (i in 0 until (la?.length() ?: 0)) la?.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        ModelMeta(
            version = o.optString("version"),
            available = true,
            force = o.optBoolean("force", false),
            minAppVersion = o.optString("min_app_version"),
            notes = o.optString("notes"),
            files = files,
            signature = o.optString("signature"),
            labels = labels,
            expireAt = o.optLong("expire_at", 0L)
        )
    }.onFailure {
        NekoLog.warn(NekoLog.MODULE_UPDATE, "update_parse_failed", it.javaClass.simpleName + ": " + it.message)
    }.getOrNull()

    /** query 值里的保留字符必须转义，否则签名串与真实 URL 会对不上 */
    private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
