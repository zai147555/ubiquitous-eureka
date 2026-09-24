package com.nekonyan.assistant.data.repo

import android.content.Context

/**
 * 语音设置。
 *
 * 官方 Edge TTS 是**端上直连微软公共服务器**，所以这里没有"服务地址"这一项 ——
 * 用户不需要部署任何东西，装好就能用（这与"RVC 变声"不同，后者仍需自建服务）。
 */
data class VoiceSettings(
    /** 用微软官方语音（默认开：零部署、音质好） */
    val edgeEnabled: Boolean = true,
    val edgeVoice: String = "zh-CN-XiaoxiaoNeural",
    /** 官方格式，带正负号，如 "+0%" / "-20%" */
    val edgeRate: String = "+0%",
    val edgePitch: String = "+0Hz",
    /** RVC 变声服务地址（可选；只对系统 TTS 生效，因为 RVC 要 WAV 而官方语音给的是 MP3） */
    val rvcUrl: String = "",
    val rvcModel: String = "",
    val autoSpeak: Boolean = false
)

/** 语音服务配置（官方语音音色 / RVC 地址 / 是否自动朗读）。都不是密钥，用普通 prefs 即可。 */
class VoiceSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nekonyan_voice", Context.MODE_PRIVATE)

    /** 一次读全，避免调用点各读各的导致状态不一致 */
    fun load(): VoiceSettings = VoiceSettings(
        edgeEnabled = prefs.getBoolean(KEY_EDGE_ON, true),
        edgeVoice = prefs.getString(KEY_EDGE_VOICE, VoiceSettings().edgeVoice)!!,
        edgeRate = prefs.getString(KEY_EDGE_RATE, "+0%")!!,
        edgePitch = prefs.getString(KEY_EDGE_PITCH, "+0Hz")!!,
        rvcUrl = rvcUrl(),
        rvcModel = modelName(),
        autoSpeak = autoSpeak()
    )

    fun rvcUrl(): String = prefs.getString(KEY_URL, "").orEmpty()
    fun modelName(): String = prefs.getString(KEY_MODEL, "").orEmpty()
    fun autoSpeak(): Boolean = prefs.getBoolean(KEY_AUTO, false)

    fun save(url: String, model: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    /** 保存官方语音设置（界面上的语音卡片用） */
    fun saveEdge(enabled: Boolean, voice: String, rate: String, pitch: String) {
        prefs.edit()
            .putBoolean(KEY_EDGE_ON, enabled)
            .putString(KEY_EDGE_VOICE, voice.trim())
            .putString(KEY_EDGE_RATE, rate.trim())
            .putString(KEY_EDGE_PITCH, pitch.trim())
            .apply()
    }

    fun setAutoSpeak(on: Boolean) = prefs.edit().putBoolean(KEY_AUTO, on).apply()

    private companion object {
        const val KEY_URL = "rvc_url"
        const val KEY_MODEL = "rvc_model"
        const val KEY_AUTO = "auto_speak"
        const val KEY_EDGE_ON = "edge_enabled"
        const val KEY_EDGE_VOICE = "edge_voice"
        const val KEY_EDGE_RATE = "edge_rate"
        const val KEY_EDGE_PITCH = "edge_pitch"
    }
}
