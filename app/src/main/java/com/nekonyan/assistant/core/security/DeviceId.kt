package com.nekonyan.assistant.core.security

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备指纹（需求：设备标识使用哈希且不可逆，与账号解绑；日志中不输出明文）
 *
 * 做法：ANDROID_ID + 包名 + 固定盐 做 SHA-256，取前 32 位十六进制。
 * 说明：ANDROID_ID 在 Android 8+ 是「按应用签名 + 用户」隔离的，本身已不可跨应用关联；
 * 再加盐哈希后，即便服务端拿到也无法反查设备。
 */
object DeviceId {

    private const val SALT_PREFIX = "nekonyan-device-v1"

    @Volatile
    private var cached: String? = null

    fun hash(context: Context): String {
        cached?.let { return it }
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "unknown"
        val salted = "$SALT_PREFIX:$raw:${context.packageName}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(salted.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        cached = hex
        return hex
    }
}
