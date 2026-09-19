package com.nekonyan.assistant.core.net

import com.nekonyan.assistant.core.util.SigningCanonical
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okio.Buffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名器（客户端唯一实现，服务端 model_dist_server.py 与之逐字节一致）
 *
 * ── 规范 v2 ──────────────────────────────────────────────────
 *   canonical = METHOD \n PATH \n QUERY \n TIMESTAMP \n NONCE \n BODY_HASH
 *     PATH      : 仅路径，不含 query
 *     QUERY     : 按 (key,value) 升序排序的 "k=v&k=v"，无 query 时为空串
 *     BODY_HASH : GET 为空字节的 sha256；POST /data/upload 为 **payload 字段原文** 的 sha256
 *                 （multipart 的 boundary 随机，无法对整个 body 复现哈希）
 *   signature = hex(HMAC-SHA256(secret, canonical))
 *
 * ★ v1 只签 method/path，**不含 query**，导致 /model/latest 的 device_id（灰度分桶）
 *   与 current 可被中间人篡改；v2 把 QUERY 纳入签名。
 *
 * 设计：做成**持有 Token 与密钥的实例**，而不是无状态 object —— 否则每个调用点都要
 * 自己保管密钥，容易泄漏也容易传错参数（这正是上一版 ModelUpdater 编译失败的根因）。
 * 凭据应由上层从 Keystore 取出后构造本对象，用完即弃。
 */
class RequestSigner(
    private val token: String,
    private val secret: String
) {

    data class Headers(
        val token: String, val timestamp: String, val nonce: String, val signature: String
    )

    /** GET 等无体请求：空体的 sha256（与服务端共用同一常量定义） */
    private val emptyBodyHash: String = SigningCanonical.EMPTY_BODY_SHA256

    // ---------------- 核心 ----------------

    fun sign(
        method: String,
        path: String,
        query: String,
        signedBody: String? = null,
        nonce: String = newNonce(),
        timestampMs: Long = System.currentTimeMillis()
    ): Headers {
        val ts = timestampMs.toString()
        val bodyHash = signedBody?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) } ?: emptyBodyHash
        // 拼接逻辑集中在 SigningCanonical（纯 Kotlin，已被单元测试 + Python 端交叉比对覆盖）
        val canonical = SigningCanonical.canonical(method, path, query, ts, nonce, bodyHash)
        return Headers(token, ts, nonce, hmacSha256Hex(secret, canonical))
    }

    /** 由完整 URL 构造（自动拆分 path 与排序后的 query） */
    fun signUrl(
        method: String,
        url: String,
        signedBody: String? = null,
        nonce: String = newNonce(),
        timestampMs: Long = System.currentTimeMillis()
    ): Headers {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("非法 URL: $url")
        return sign(
            method = method,
            path = parsed.encodedPath,
            query = canonicalQuery(parsed),
            signedBody = signedBody,
            nonce = nonce,
            timestampMs = timestampMs
        )
    }

    /** 一步到位：给 OkHttp Request.Builder 挂上四个签名头 */
    fun signInto(
        builder: Request.Builder,
        method: String,
        url: String,
        signedBody: String? = null
    ): Request.Builder {
        val h = signUrl(method, url, signedBody)
        return builder
            .header("X-API-Token", h.token)
            .header("X-Timestamp", h.timestamp)
            .header("X-Nonce", h.nonce)
            .header("X-Signature", h.signature)
    }

    /**
     * OkHttp 拦截器：自动为所有请求挂签名头。
     *
     * Retrofit 接口（如 UpdateApi.fetchLatest）拿不到「构造完成的 URL + body」，
     * 只能在这一层签名 —— 直接调 [signInto] 会漏掉 query 变化。
     *
     * ⚠️ 签名覆盖 query，因此拦截器必须加在「添加 query 参数的拦截器之后」。
     * body 处理：JSON 请求体整体参与签名；multipart 交给调用方显式处理。
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val contentType = req.body?.contentType()?.toString().orEmpty()
        if (req.header("X-Signature") != null || contentType.startsWith("multipart/")) {
            return@Interceptor chain.proceed(req)
        }
        val signedBody = req.body?.let { body ->
            val buf = Buffer()
            body.writeTo(buf)
            buf.readUtf8()
        }
        val h = signUrl(req.method, req.url.toString(), signedBody)
        chain.proceed(
            req.newBuilder()
                .header("X-API-Token", h.token)
                .header("X-Timestamp", h.timestamp)
                .header("X-Nonce", h.nonce)
                .header("X-Signature", h.signature)
                .build()
        )
    }

    // ---------------- 工具 ----------------

    /** 规范化 query：排序规则只有一处实现（SigningCanonical），避免两端漂移 */
    fun canonicalQuery(url: HttpUrl): String {
        val pairs = ArrayList<Pair<String, String>>()
        for (i in 0 until url.querySize) {
            pairs.add(url.queryParameterName(i) to (url.queryParameterValue(i) ?: ""))
        }
        return SigningCanonical.canonicalQuery(pairs)
    }

    companion object {
        fun newNonce(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun hmacSha256Hex(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
