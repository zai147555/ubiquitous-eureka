package com.nekonyan.assistant.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.repo.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 「说话」链路，两档回退：
 *
 *   ① **微软官方 Edge TTS**（端上直连公共服务，零部署）→ MP3 → 直接播；
 *   ② 失败则回退**系统 TTS** → WAV →（可选）RVC 变声 → 播。
 *
 * 四处刻意的设计：
 *   ① **官方语音优先**：不需要用户部署服务器、也不需要设备装中文语音包，音质更好；
 *   ② **RVC 只挂在系统 TTS 这一档**：RVC 要 WAV 输入，而官方语音给的是 MP3，
 *      端上转格式要额外依赖 ffmpeg —— 所以官方语音生效时不做变声（如实写在返回文案里）；
 *   ③ **合成落文件而不是直接 speak**：要把字节交给播放器（以及 RVC）；
 *   ④ **两条路都失败时说人话**：带上官方服务的失败原因与"设备缺中文语音包"这类具体原因，
 *      而不是静默无声。
 */
class VoicePipeline(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null

    /** 官方 Edge TTS 客户端（无状态，可复用：每次合成都新建一条 WebSocket） */
    private val edge = EdgeTtsClient()

    @Volatile var ready: Boolean = false
        private set

    /** 初始化 TTS；回调在主线程（TextToSpeech 的约定） */
    fun init(onReady: (Boolean) -> Unit) {
        if (tts != null) { onReady(ready); return }
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            ready = ok
            if (ok) {
                val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    NekoLog.warn(NekoLog.MODULE_AI, "tts_lang_missing", "设备缺少中文 TTS 语音包")
                }
                NekoLog.info(NekoLog.MODULE_AI, "tts_ready", "TTS 初始化成功")
            } else {
                NekoLog.error(NekoLog.MODULE_AI, "tts_init_failed", "status=$status")
            }
            onReady(ok)
        }
    }

    /**
     * 朗读一段文字。
     * @param rvc 配好则走变声，null 则播 TTS 原声
     * @return 人话结果（界面直接显示）
     */
    suspend fun speak(text: String, settings: VoiceSettings): String {
        val clean = text.trim()
        if (clean.isEmpty()) return "没有可朗读的内容"

        // ---- 第一档：微软官方语音（端上直连） ----
        var edgeFailure: String? = null
        if (settings.edgeEnabled) {
            when (val r = withContext(Dispatchers.IO) {
                edge.synthesize(clean, settings.edgeVoice, settings.edgeRate, settings.edgePitch)
            }) {
                is EdgeTtsClient.Result.Ok -> {
                    play(r.mp3, "mp3")
                    val short = settings.edgeVoice.substringAfterLast('-').removeSuffix("Neural")
                    return "已用微软官方语音播放（$short，首帧 ${r.firstByteMs}ms）"
                }
                is EdgeTtsClient.Result.Err -> {
                    edgeFailure = r.message
                    NekoLog.warn(NekoLog.MODULE_AI, "edge_tts_failed", r.message)
                }
            }
        }

        // ---- 第二档：系统 TTS（可选 RVC） ----
        val engine = tts
        if (engine == null || !ready) {
            return edgeFailure?.let { "官方语音失败（$it），设备也没有可用的语音引擎（设置 → 无障碍/语言 里装一个 TTS）" }
                ?: "设备没有可用的语音引擎（设置 → 无障碍/语言 里装一个 TTS）"
        }
        val rvc = settings.rvcUrl.takeIf { it.isNotBlank() }?.let { RvcClient(it) }

        val out = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(true) }
            @Deprecated("老的错误回调")
            override fun onError(utteranceId: String?) { done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
        })
        val started = engine.synthesizeToFile(clean, Bundle(), out, "neko_${System.currentTimeMillis()}")
        if (started != TextToSpeech.SUCCESS) return "TTS 合成启动失败"
        val ok = withContext(Dispatchers.IO) { done.await() }
        if (!ok || !out.exists() || out.length() == 0L) {
            out.delete()
            return "TTS 合成失败（可能是缺少中文语音包）"
        }

        val audio: ByteArray = withContext(Dispatchers.IO) { out.readBytes() }
        val converted = rvc?.let { withContext(Dispatchers.IO) { it.convert(audio) } }
        val toPlay = converted ?: audio
        val label = if (converted != null) "已用 RVC 变声后播放"
                    else if (rvc != null) "RVC 转换失败，播放系统 TTS 原声"
                    else if (edgeFailure != null) "官方语音失败（$edgeFailure），已回退系统 TTS"
                    else "已播放系统 TTS 原声"
        play(toPlay, "wav")
        out.delete()
        return label
    }

    /** 播放音频字节（用缓存文件喂 MediaPlayer；扩展名要与真实格式一致） */
    private suspend fun play(bytes: ByteArray, ext: String) {
        val f = File(context.cacheDir, "play_${System.currentTimeMillis()}.$ext")
        withContext(Dispatchers.IO) { f.writeBytes(bytes) }
        withContext(Dispatchers.Main) {
            runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { p -> p.release(); f.delete() }
                    prepare()
                    start()
                }
            }.onFailure {
                NekoLog.warn(NekoLog.MODULE_AI, "voice_play_failed", it.javaClass.simpleName)
                f.delete()
            }
        }
    }

    fun release() {
        runCatching { player?.release() }
        runCatching { tts?.shutdown() }
        player = null
        tts = null
        ready = false
    }
}
