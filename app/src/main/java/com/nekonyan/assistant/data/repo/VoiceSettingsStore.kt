package com.nekonyan.assistant.data.repo

import android.content.Context

/** 语音服务配置（RVC 地址 / 音色 / 是否自动朗读）。地址不是密钥，用普通 prefs 即可。 */
class VoiceSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nekonyan_voice", Context.MODE_PRIVATE)

    fun rvcUrl(): String = prefs.getString(KEY_URL, "").orEmpty()
    fun modelName(): String = prefs.getString(KEY_MODEL, "").orEmpty()
    fun autoSpeak(): Boolean = prefs.getBoolean(KEY_AUTO, false)

    fun save(url: String, model: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    fun setAutoSpeak(on: Boolean) = prefs.edit().putBoolean(KEY_AUTO, on).apply()

    private companion object {
        const val KEY_URL = "rvc_url"
        const val KEY_MODEL = "rvc_model"
        const val KEY_AUTO = "auto_speak"
    }
}
