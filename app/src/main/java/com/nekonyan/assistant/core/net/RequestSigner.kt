package com.nekonyan.assistant.core.net

import com.nekonyan.assistant.core.util.SigningCanonical
import okhttp3.HttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名（客户端唯一实现，服务端 model_dist_server.py 与之逐字节对齐）
 *
 * ── 规范 v2（★ 相对旧实现的修正）─────────────────────────────
 * 旧规范只签 method\npath\nts\nnonce\nbodyHash，**path 不含 query**，
 * 导致 /model/latest 的 device_id（灰度分桶）、current 可被中间人篡改。
 *
 * 新规范把「规范化 query」纳入签名：
 *
 *   canonical = METHOD \n
 *               PATH                       ← 仅 path，不含 query
 *               \n QUERY                   ← 排序后的 k=v&k=v（无 query 则空串）
 *               \n TIMESTAMP \n NONCE \n
 *               BODY_HASH                  ← POST：签名字段原文的 sha256；GET：空串的 sha256
 *
 *   signature = hex(HMAC-SHA256(secret, canonical))
 *
 * POST /data/upload 的 payload 是 multipart 的一个字段：直接对整个 multipart body
 * 做哈希会因 boundary 随机而无法复现，因此约定**只签 payload 字段原文**，
 * 文件本体由 Token + TLS 保护。
 */
object RequestSigner {

    /** GET 等无体请求：空体的 sha256（与服务端共用同一常量，避免两处定义漂移） */
    private val EMPTY_BODY_HASH: String = SigningCanonical.EMPTY_BODY_SHA256

    data class Headers(
        val token: String, val timestamp: String, val nonce: String, val signature: String
    )

    fun sign(
        method: String,
        url: String,
        token: String,
        secret: String,
        /** POST 时为签名字段原文；GET 传 null */
        signedBody: String? = null,
        nonce: String = newNonce(),
        timestampMs: Long = System.currentTimeMillis()
    ): Headers {
        val parsed = HttpUrl.parse(url) ?: throw IllegalArgumentException("非法 URL: $url")
        return sign(
            method = method,
            path = parsed.encodedPath,
            query = canonicalQuery(parsed),
            token = token,
            secret = secret,
            signedBody = signedBody,
            nonce = nonce,
            timestampMs = timestampMs
        )
    }

    fun sign(
        method: String,
        path: String,
        query: String,
        token: String,
        secret: String,
        signedBody: String?,
        nonce: String = newNonce(),
        timestampMs: Long = System.currentTimeMillis()
    ): Headers {
        val ts = timestampMs.toString()
        val bodyHash = signedBody?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) } ?: EMPTY_BODY_HASH
        // ★ 显式 UTF-8：不依赖平台默认字符集
        // ★ 拼接逻辑集中在 SigningCanonical（纯 Kotlin，已被单元测试 + Python 端交叉比对覆盖）
        val canonical = SigningCanonical.canonical(method, path, query, ts, nonce, bodyHash)
        return Headers(token, ts, nonce, hmacSha256Hex(secret, canonical))
    }

    /** 一步到位：给 OkHttp Request.Builder 挂上四个签名头 */
    fun signInto(
        builder: Request.Builder,
        method: String,
        url: String,
        token: String,
        secret: String,
        signedBody: String? = null
    ): Request.Builder {
        val h = sign(method, url, token, secret, signedBody)
        return builder
            .header("X-API-Token", h.token)
            .header("X-Timestamp", h.timestamp)
            .header("X-Nonce", h.nonce)
            .header("X-Signature", h.signature)
    }

    /**
     * 规范化 query：按 key 升序，key 相同按 value 升序，`k=v` 用 `&` 连接。
     * 无值参数统一写成 `k=`（不省略），保证两端逐字节一致。
     */
    fun canonicalQuery(url: HttpUrl): String {
        val pairs = ArrayList<Pair<String, String>>()
        for (i in 0 until url.querySize) {
            pairs.add(url.queryParameterName(i) to (url.queryParameterValue(i) ?: ""))
        }
        // 排序规则只有一处实现（SigningCanonical），避免两端/两处漂移
        return SigningCanonical.canonicalQuery(pairs)
    }

    fun newNonce(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    /**
     * OkHttp 拦截器：自动为所有请求挂签名头。
     *
     * Retrofit 接口（如 UpdateApi.fetchLatest）拿不到「构造完成的 URL + body」，
     * 只能在这一层签名 —— 直接调用 [signInto] 会漏掉 query 变化。
     *
     * ⚠️ 签名覆盖 query，因此**拦截器必须加在「添加 query 参数的拦截器之后」**，
     *    否则签的是半成品 URL，服务端一定验签失败。
     *
     * 用法：
     *   val client = OkHttpClient.Builder()
     *       .addInterceptor(RequestSigner.interceptor(token, secret))
     *       .build()
     *   Retrofit.Builder().client(client)...
     *
     * body 处理：JSON 请求体（application/json）会整体参与签名，与服务端
     * 「按 Content-Type 取 body」的约定一致；multipart 不在此拦截器处理
     * （必须显式调 [signInto] 并传 signedBody = payload）。
     */
    fun interceptor(token: String, secret: String): okhttp3.Interceptor =
        okhttp3.Interceptor { chain ->
            val req = chain.request()
            val contentType = req.body?.contentType()?.toString().orEmpty()
            if (req.header("X-Signature") != null || contentType.startsWith("multipart/")) {
                return@Interceptor chain.proceed(req)      // 已签名 / multipart 自行处理
            }
            val signedBody = if (req.body == null) null else {
                val buf = okio.Buffer()
                req.body!!.writeTo(buf)
                buf.readUtf8()
            }
            val h = sign(
                method = req.method,
                url = req.url.toString(),
                token = token,
                secret = secret,
                signedBody = signedBody
            )
            chain.proceed(
                req.newBuilder()
                    .header("X-API-Token", h.token)
                    .header("X-Timestamp", h.timestamp)
                    .header("X-Nonce", h.nonce)
                    .header("X-Signature", h.signature)
                    .build()
            )
        }

    // ---------------- 基础算法 ----------------

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
