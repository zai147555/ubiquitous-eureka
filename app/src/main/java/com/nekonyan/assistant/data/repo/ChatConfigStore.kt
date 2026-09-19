package com.nekonyan.assistant.data.repo

import android.content.Context
import com.nekonyan.assistant.core.chat.ChatConfig
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.security.SecurityStore

/**
 * 对话配置的落地存储。
 *
 * 分工刻意分成两处：
 *   · **API Key / API 地址** → [SecurityStore]（Android Keystore + AES-GCM，密钥不出 Keystore）；
 *   · 模型名 / 超时 / 温度 / 最大输出 → 普通 SharedPreferences（不是敏感信息，无需加密）。
 * 这样即使数据库或 prefs 被导出，也拿不到 Key 明文。
 */
class ChatConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(): ChatConfig = ChatConfig(
        baseUrl = SecurityStore.getSecret(appContext, SecurityStore.KEY_API_BASE)
            ?: ChatConfig.DEFAULT_BASE_URL,
        apiKey = SecurityStore.getSecret(appContext, SecurityStore.KEY_API_KEY).orEmpty(),
        model = prefs.getString(KEY_MODEL, null) ?: ChatConfig.DEFAULT_MODEL,
        timeoutSeconds = prefs.getInt(KEY_TIMEOUT, ChatConfig.DEFAULT_TIMEOUT_SECONDS),
        temperature = prefs.getFloat(KEY_TEMPERATURE, ChatConfig.DEFAULT_TEMPERATURE.toFloat()).toDouble(),
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, ChatConfig.DEFAULT_MAX_TOKENS)
    ).normalized()

    fun save(config: ChatConfig) {
        val c = config.normalized()
        SecurityStore.putSecret(appContext, SecurityStore.KEY_API_BASE, c.baseUrl)
        SecurityStore.putSecret(appContext, SecurityStore.KEY_API_KEY, c.apiKey)
        prefs.edit()
            .putString(KEY_MODEL, c.model)
            .putInt(KEY_TIMEOUT, c.timeoutSeconds)
            .putFloat(KEY_TEMPERATURE, c.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS, c.maxTokens)
            .apply()
        NekoLog.info(NekoLog.MODULE_STORE, "chat_config_saved", c.describeForLog())
    }

    fun hasApiKey(): Boolean = SecurityStore.hasApiKey(appContext)

    fun clearApiKey() {
        SecurityStore.putSecret(appContext, SecurityStore.KEY_API_KEY, null)
    }

    private companion object {
        const val PREF = "nekonyan_chat"
        const val KEY_MODEL = "model"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
    }
}
