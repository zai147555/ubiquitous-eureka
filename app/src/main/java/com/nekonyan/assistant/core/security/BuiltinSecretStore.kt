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
 * 随包内置的 YOLO 服务凭据（密文放在 `assets/yolo_service.enc`）。
 *
 * ⚠️ **性质说明（必须诚实）**：解密口令 `PASS` 只能写在 APK 里，否则应用自己解不开。
 * 所以这不是密码学意义上的安全，只是"**不让明文出现在公开仓库和安装包里**" ——
 * 能反编译 APK 的人依然拿得到。真正的保密只能靠：HTTPS + 端口白名单 + 定期轮换 Token。
 *
 * 策略：**用户手填的值优先**。只有 Keystore 里为空时才把内置值写进去，
 * 之后一切与手填完全一样（读出来是 Keystore 解密后的值）。
 *
 * 加密参数与本项目服务端的 `密钥.enc` 保持同一套：
 * AES-256-GCM + PBKDF2-HMAC-SHA256(200000) + 12 字节 nonce + 密文含 16 字节 tag。
 */
object BuiltinSecretStore {

    private const val ASSET = "yolo_service.enc"
    private const val PASS = "nekonyan-yolo-service-v1"
    private const val GCM_TAG_BITS = 128

    /** 返回 (base_url, api_token)；任何失败都返回 null 并留日志，绝不抛给界面 */
    fun load(context: Context): Pair<String, String>? = runCatching {
        val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
        val o = JSONObject(text)
        val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
        val nonce = Base64.decode(o.getString("nonce"), Base64.NO_WRAP)
        val ct = Base64.decode(o.getString("ciphertext"), Base64.NO_WRAP)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(PASS.toCharArray(), salt, o.optInt("iterations", 200_000), 256)
            ).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        val plain = String(cipher.doFinal(ct), Charsets.UTF_8)
        val j = JSONObject(plain)
        j.getString("base_url") to j.getString("api_token")
    }.onFailure {
        NekoLog.warn(
            NekoLog.MODULE_SECURITY, "builtin_cred_failed",
            it.javaClass.simpleName + ": " + it.message
        )
    }.getOrNull()
}
