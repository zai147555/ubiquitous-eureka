package com.nekonyan.assistant.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 「说话」链路：文字 → TTS 合成 WAV → （可选）RVC 变声 → 播放。
 *
 * 三处刻意的设计：
 *   ① **RVC 是可选的一跳**：没配 RVC 地址时直接播 TTS 原声 —— 功能立刻可用，
 *      配好之后再"升级"成目标音色。不会因为外部服务没起就整个功能不可用；
 *   ② **TTS 合成落文件**（`synthesizeToFile`）而不是直接 speak：因为要拿到 WAV 字节送给 RVC；
 *   ③ **拿不到中文 TTS 引擎要说人话**，而不是静默无声（安卓的 TTS 引擎是可选组件）。
 */
class VoicePipeline(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null

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
    suspend fun speak(text: String, rvc: RvcClient?): String {
        val engine = tts ?: return "TTS 未初始化"
        if (!ready) return "设备没有可用的语音引擎（设置 → 无障碍/语言 里装一个 TTS）"
        val clean = text.trim()
        if (clean.isEmpty()) return "没有可朗读的内容"

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
        val label = if (converted != null) "已用 RVC 变声后播放" else if (rvc != null) "RVC 转换失败，播放 TTS 原声" else "已播放 TTS 原声"
        play(toPlay)
        out.delete()
        return label
    }

    /** 播放 WAV 字节（用缓存文件喂 MediaPlayer） */
    private suspend fun play(bytes: ByteArray) {
        val f = File(context.cacheDir, "play_${System.currentTimeMillis()}.wav")
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
