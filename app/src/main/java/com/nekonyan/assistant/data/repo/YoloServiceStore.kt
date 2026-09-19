package com.nekonyan.assistant.data.repo

import android.content.Context
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.security.SecurityStore

/**
 * 「模型/」那套 YOLO 检测服务的凭据存储。
 *
 * 地址与 Token 都走 [SecurityStore]（Android Keystore + AES-GCM）：
 * 接入指南第 382~384 行明确要求**不要把 Token 硬编码进前端、不要提交到公开仓库**，
 * 因此这里既不落明文 prefs，也从不把 Token 传给日志（日志里只有"已配置/未配置"与长度）。
 */
class YoloServiceStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun baseUrl(): String = SecurityStore.getSecret(appContext, KEY_BASE).orEmpty()

    fun token(): String = SecurityStore.getSecret(appContext, KEY_TOKEN).orEmpty()

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun save(baseUrl: String, token: String) {
        val b = baseUrl.trim().trimEnd('/')
        SecurityStore.putSecret(appContext, KEY_BASE, b)
        SecurityStore.putSecret(appContext, KEY_TOKEN, token.trim())
        NekoLog.info(
            NekoLog.MODULE_STORE, "yolo_service_saved",
            "base=$b, token=${if (token.isBlank()) "未配置" else "已配置(len=${token.trim().length})"}"
        )
    }

    private companion object {
        const val PREF = "nekonyan_yolo_service"
        const val KEY_ENABLED = "enabled"
        const val KEY_BASE = "yolo_service_base"
        const val KEY_TOKEN = "yolo_service_token"
    }
}
