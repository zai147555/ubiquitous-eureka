package com.nekonyan.assistant.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nekonyan.assistant.core.log.NekoLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感配置加密存储（需求：API Key 加密存储 Android Keystore；截图/聊天记录可选加密；
 * 一键清空本地数据；日志脱敏）
 *
 * 实现：AES-256-GCM，密钥由 Android Keystore 生成并保管（**密钥不出 Keystore**，
 * 即使 APK 被反编译也拿不到明文密钥）。密文格式 = base64(iv || ciphertext)。
 *
 * 注意：这里存的是"应用自己的密钥"，与 DeepSeek 的 API Key 是两回事。
 * 需求里的「Token 轮换 / 证书绑定」在本类之外（core/net）。
 */
object SecurityStore {

    private const val TAG = "SecurityStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "nekonyan_master_v1"
    private const val PREF = "nekonyan_secure"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    const val KEY_API_KEY = "deepseek_api_key"
    const val KEY_API_BASE = "deepseek_api_base"

    // ---------------- 对外 API ----------------

    fun hasApiKey(context: Context): Boolean = !getSecret(context, KEY_API_KEY).isNullOrBlank()

    /** 写入敏感值（空值等于删除） */
    fun putSecret(context: Context, name: String, value: String?) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            NekoLog.info(NekoLog.MODULE_SECURITY, "secret_cleared", name)
            return
        }
        runCatching {
            prefs.edit().putString(name, encrypt(value)).apply()
            NekoLog.info(NekoLog.MODULE_SECURITY, "secret_saved", name)
        }.onFailure {
            NekoLog.error(NekoLog.MODULE_SECURITY, "secret_save_failed", "${name}: ${it.message}")
        }
    }

    /** 读取敏感值；解密失败返回 null（例如系统重置了 Keystore 密钥） */
    fun getSecret(context: Context, name: String): String? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(name, null)
            ?: return null
        return runCatching { decrypt(raw) }.getOrElse {
            NekoLog.error(NekoLog.MODULE_SECURITY, "secret_decrypt_failed",
                "$name: ${it.message}（Keystore 密钥可能已被系统重置，需重新填写）")
            null
        }
    }

    /** 需求：一键清空所有本地数据（此处仅清敏感配置，数据库清空由设置页统一编排） */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { keyStore().deleteEntry(MASTER_KEY_ALIAS) }
        NekoLog.warn(NekoLog.MODULE_SECURITY, "secure_store_cleared", "已清空敏感配置与主密钥")
    }

    // ---------------- 加解密 ----------------

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun masterKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 需求：不要求用户每次解锁，但密钥不可导出
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(IV_LEN + ct.size)
        System.arraycopy(iv, 0, out, 0, IV_LEN)
        System.arraycopy(ct, 0, out, IV_LEN, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        require(all.size > IV_LEN) { "密文长度非法" }
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
