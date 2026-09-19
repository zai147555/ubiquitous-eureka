package com.nekonyan.assistant.core.util

/**
 * 请求签名的规范化串（纯 Kotlin，无 Android/OkHttp 依赖）
 *
 * ★ 这是端云之间**唯一必须逐字节一致**的东西：服务端 model_dist_server.py 的
 *   _verify_sig() 拼的是同一个串。放到这里是为了让它能被单元测试直接覆盖 ——
 *   签名规范写错的表现是"上线后全部 401"，很难在真机上定位。
 *
 *   canonical = METHOD \n PATH \n QUERY \n TIMESTAMP \n NONCE \n BODY_HASH
 *     PATH      : 仅路径，不含 query
 *     QUERY     : 按 (key, value) 升序排序的 "k=v&k=v"；无 query 时为空串
 *     BODY_HASH : GET 为空字节的 sha256；POST /data/upload 为 payload 字段原文的 sha256
 */
object SigningCanonical {

    val EMPTY_BODY_SHA256: String =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun canonical(
        method: String,
        path: String,
        query: String,
        timestamp: String,
        nonce: String,
        bodyHash: String
    ): String = listOf(method, path, query, timestamp, nonce, bodyHash).joinToString("\n")

    /**
     * 规范化一组 query 参数：按 key 升序，key 相同按 value 升序。
     * 空值统一写成 `k=`，两端保持一致（不能一边省略一边补等号）。
     */
    fun canonicalQuery(params: List<Pair<String, String>>): String =
        params.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }
}
