package com.nekonyan.assistant.core.security

import android.content.Context
import android.util.Base64
import com.nekonyan.assistant.core.log.NekoLog
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 随包内置的服务凭据（密文放在 `assets/yolo_service.enc`）。
 *
 * ⚠️ **性质说明（必须诚实）**：解密口令 `PASS` 只能写在 APK 里，否则应用自己解不开。
 * 所以这不是密码学意义上的安全，只是"**不让明文出现在公开仓库和安装包里**"。
 * 真正的保密靠：HTTPS（两个域名都已走 Cloudflare 隧道）+ 定期轮换 Token。
 *
 * 密文里目前含：`base_url`（检测服务）、`api_token`、`model_base_url`（热更新服务）、`sign_secret`。
 * 加密参数与 [SecurityStore]、服务端密钥包保持同一套：
 * AES-256-GCM + PBKDF2-HMAC-SHA256(200000) + 12 字节 nonce + 密文含 16 字节 tag。
 */
object BuiltinSecretStore {

    private const val ASSET = "yolo_service.enc"
    private const val PASS = "nekonyan-yolo-service-v1"
    private const val GCM_TAG_BITS = 128

    /** 解密后的完整凭据；任何失败返回 null 并留日志，绝不抛给界面 */
    fun loadFull(context: Context): JSONObject? = runCatching {
        JSONObject(decrypt(context))
    }.onFailure {
        NekoLog.warn(NekoLog.MODULE_SECURITY, "builtin_cred_failed", it.javaClass.simpleName + ": " + it.message)
    }.getOrNull()

    /** 检测服务的 (base_url, api_token) */
    fun load(context: Context): Pair<String, String>? = loadFull(context)?.let { j ->
        runCatching { j.getString("base_url") to j.getString("api_token") }.getOrNull()
    }

    /** 热更新服务的 (model_base_url, api_token, sign_secret)；任一为空则返回 null */
    fun loadModelService(context: Context): Triple<String, String, String>? = loadFull(context)?.let { j ->
        val base = j.optString("model_base_url")
        val token = j.optString("api_token")
        val secret = j.optString("sign_secret")
        if (base.isBlank() || token.isBlank() || secret.isBlank()) null else Triple(base, token, secret)
    }

    /** 解密 assets 里的密文 → 明文 JSON 字符串；失败抛异常，由上面的 runCatching 兜住 */
    private fun decrypt(context: Context): String {
        val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
        val o = JSONObject(text)
        val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
        val nonce = Base64.decode(o.getString("nonce"), Base64.NO_WRAP)
        val ct = Base64.decode(o.getString("ciphertext"), Base64.NO_WRAP)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(PASS.toCharArray(), salt, o.optInt("iterations", 200_000), 256))
            .encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
