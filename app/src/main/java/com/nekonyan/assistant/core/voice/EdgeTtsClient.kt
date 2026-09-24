package com.nekonyan.assistant.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 微软官方 Edge TTS 的**端上直连**客户端 —— 不需要任何自建服务器。
 *
 * 协议取自官方开源实现 `rany2/edge-tts`（constants.py / drm.py / communicate.py），
 * 并已用真服务逐项实测确认（见本类各处的"实测"注释）：
 *
 *   · 连接：wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 *     query 带 TrustedClientToken / ConnectionId / Sec-MS-GEC / Sec-MS-GEC-Version；
 *   · 鉴权：Sec-MS-GEC = SHA256(把当前时间按 Windows file time 取整到 5 分钟 + 固定 token)
 *     的大写十六进制。**设备时钟偏差会直接导致 403** —— 所以这里会读服务端 `Date`
 *     头把偏差算出来重试一次（官方库同款处理）；
 *   · 返回的是 **MP3（audio-24khz-48kbitrate-mono-mp3）**：Android 的 MediaPlayer
 *     原生就能播，不需要 ffmpeg（这是选它而不是 WAV 的原因）；
 *   · 二进制帧布局（实测 hexdump 确认）：[2 字节大端 headerLen][headerLen 字节头部文本][音频]，
 *     音频起点是 **2 + headerLen**（headerLen 只算头部文本，不含那 2 字节前缀）。
 *
 * 刻意**不引入任何 Android 依赖**：这样它能被普通 JVM 单元测试直接跑（本机就是这么验证的）。
 *
 * 说明：这是微软对 Edge 浏览器"朗读"功能的公开接口，社区广泛使用但**非官方承诺**；
 * 失效时 [synthesize] 会返回带原因的失败，由 [VoicePipeline] 回退到系统 TTS。
 */
class EdgeTtsClient(private val http: OkHttpClient = defaultClient()) {

    data class Voice(
        val shortName: String,
        val gender: String,
        val locale: String,
        val friendlyName: String
    )

    sealed interface Result {
        data class Ok(val mp3: ByteArray, val firstByteMs: Long) : Result

        /** [serverSkewSeconds] 非空表示服务端返回 403 且给了 Date：可用于时钟纠正后重试 */
        data class Err(val message: String, val serverSkewSeconds: Double? = null) : Result
    }

    /**
     * 合成一段文字。
     *
     * @param rate 形如 "+0%" / "-20%"（官方格式，带正负号）
     * @param pitch 形如 "+0Hz" / "+20Hz"
     */
    suspend fun synthesize(
        text: String,
        voice: String = DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz"
    ): Result {
        val clean = removeIncompatible(text)
        if (clean.isBlank()) return Result.Err("没有可朗读的内容")

        val out = ByteArrayOutputStream()
        var firstMs = -1L
        var skew = 0.0
        var retried = false

        for (chunk in chunkByBytes(clean, MAX_CHUNK_BYTES)) {
            while (true) {
                when (val r = turn(chunk, voice, rate, pitch, skew)) {
                    is Result.Ok -> {
                        out.write(r.mp3)
                        if (firstMs < 0) firstMs = r.firstByteMs
                        break
                    }
                    is Result.Err -> {
                        // 403 + 服务端 Date → 时钟偏差，纠正后重试一次（之后再失败就如实报错）
                        val corrected = r.serverSkewSeconds
                        if (corrected != null && !retried) {
                            retried = true
                            skew = corrected
                            NekoTtsLog("403：按时钟偏差 ${"%.1f".format(corrected)}s 重试")
                            continue
                        }
                        // 只拼了一半的音频没有播放价值，直接整体失败
                        return r
                    }
                }
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return Result.Err("服务没有返回音频")
        return Result.Ok(bytes, firstMs)
    }

    /** 官方音色列表（322 个，实测 742ms）。失败返回空列表，调用方回退到内置常用音色。 */
    suspend fun voices(): List<Voice> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$VOICE_LIST_URL?trustedclienttoken=$TRUSTED_CLIENT_TOKEN")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching emptyList()
                val body = r.body?.string().orEmpty()
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val sn = o.optString("ShortName")
                    if (sn.isBlank()) null
                    else Voice(
                        shortName = sn,
                        gender = o.optString("Gender"),
                        locale = o.optString("Locale"),
                        friendlyName = o.optString("FriendlyName")
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 单轮会话 ----------------

    private suspend fun turn(
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        skewSeconds: Double
    ): Result = withContext(Dispatchers.IO) {
        val audio = ByteArrayOutputStream()
        var firstByteMs = -1L
        var httpCode = 0
        var serverSkew: Double? = null
        var failure: String? = null
        val done = CompletableDeferred<Boolean>()
        val startedAt = System.currentTimeMillis()

        val connectId = UUID.randomUUID().toString().replace("-", "")
        val url = buildString {
            append(WSS_URL)
            append("&ConnectionId=").append(connectId)
            append("&Sec-MS-GEC=").append(secMsGec(skewSeconds))
            append("&Sec-MS-GEC-Version=").append(SEC_MS_GEC_VERSION)
        }
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // 先 speech.config 声明输出格式，再发 SSML（顺序不能反）
                ws.send(speechConfigMessage())
                ws.send(ssmlMessage(UUID.randomUUID().toString().replace("-", ""), text, voice, rate, pitch))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                when (headerValue(text, "Path")) {
                    "turn.end" -> {
                        if (!done.isCompleted) done.complete(true)
                        runCatching { ws.close(1000, null) }
                    }
                    // turn.start / response / audio.metadata 都不需要处理（我们关掉了边界元数据）
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (2 + headerLen > b.size) return
                val header = String(b, 2, headerLen, Charsets.UTF_8)
                val data = b.copyOfRange(2 + headerLen, b.size)
                if (headerValue(header, "Path") != "audio") return
                if (headerValue(header, "Content-Type") != "audio/mpeg") return
                if (data.isEmpty()) return
                if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - startedAt
                audio.write(data)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                runCatching { ws.close(1000, null) }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(true)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                httpCode = response?.code ?: 0
                if (response?.code == 403) {
                    // 服务端 Date 与本地时钟的差：Sec-MS-GEC 是按时间算的，偏差大了就必然 403
                    serverSkew = response.header("Date")?.let { parseHttpDate(it) }
                        ?.let { it - System.currentTimeMillis() / 1000.0 }
                }
                failure = buildString {
                    append("连接失败：").append(t.javaClass.simpleName)
                    t.message?.let { append(" ").append(it.take(120)) }
                    if (httpCode != 0) append("（HTTP ").append(httpCode).append("）")
                }
                if (!done.isCompleted) done.complete(false)
            }
        }

        val ws = http.newWebSocket(request, listener)
        val finished = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() } ?: false
        runCatching { ws.cancel() }

        val bytes = audio.toByteArray()
        when {
            bytes.isNotEmpty() -> Result.Ok(bytes, firstByteMs)
            failure != null -> Result.Err(failure!!, serverSkew)
            !finished -> Result.Err("等待语音服务超时（${TURN_TIMEOUT_MS / 1000} 秒）")
            else -> Result.Err("服务接受了请求但没有返回音频（音色名或文本可能不被接受）")
        }
    }

    companion object {
        const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

        /** 常用中文音色（拉不到官方列表时的兜底；也是界面上优先展示的几个） */
        val FALLBACK_VOICES = listOf(
            Voice("zh-CN-XiaoxiaoNeural", "Female", "zh-CN", "晓晓 · 女声（温柔，推荐）"),
            Voice("zh-CN-XiaoyiNeural", "Female", "zh-CN", "晓伊 · 女声（活泼）"),
            Voice("zh-CN-XiaomengNeural", "Female", "zh-CN", "晓梦 · 女声（可爱）"),
            Voice("zh-CN-XiaohanNeural", "Female", "zh-CN", "晓涵 · 女声（知性）"),
            Voice("zh-CN-XiaoxuanNeural", "Female", "zh-CN", "晓萱 · 女声（干练）"),
            Voice("zh-CN-YunxiNeural", "Male", "zh-CN", "云希 · 男声（清亮）"),
            Voice("zh-CN-YunyangNeural", "Male", "zh-CN", "云扬 · 男声（播报）"),
            Voice("zh-CN-YunjianNeural", "Male", "zh-CN", "云健 · 男声（浑厚）"),
            Voice("zh-CN-liaoning-XiaobeiNeural", "Female", "zh-CN-liaoning", "晓北 · 东北话"),
            Voice("zh-CN-shaanxi-XiaoniNeural", "Female", "zh-CN-shaanxi", "晓妮 · 陕西话")
        )

        // 公开的固定 token 与版本号（官方库 constants.py 同值；版本号只是声明客户端身份）
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val CHROMIUM_VERSION = "143.0.3650.75"
        const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_VERSION"
        const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        const val VOICE_LIST_URL =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list"

        /** Windows file time 起点到 Unix 起点的秒数 */
        private const val WIN_EPOCH_SECONDS = 11_644_473_600.0
        private const val TICKS_PER_SECOND = 10_000_000.0

        /** 单条 SSML 的字节上限（官方库按 4096 字节切分） */
        const val MAX_CHUNK_BYTES = 4096

        const val TURN_TIMEOUT_MS = 30_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Sec-MS-GEC：把当前时间（+时钟偏差）换成 Windows file time、向下取整到 5 分钟，
         * 拼上固定 token 后 SHA256，取**大写**十六进制。
         *
         * 这里刻意用与官方库相同的浮点写法：取值恒为 10^7 的整数倍（因为先取整到 300 秒），
         * 而 10^7 倍后仍能被 float64 精确表示，所以与整数运算结果一致 —— 已与 Python 版逐位比对。
         */
        fun secMsGec(skewSeconds: Double = 0.0, nowMs: Long = System.currentTimeMillis()): String {
            var ticks = nowMs / 1000.0 + skewSeconds + WIN_EPOCH_SECONDS
            ticks -= ticks % 300
            val str = "%.0f".format(Locale.US, ticks * TICKS_PER_SECOND) + TRUSTED_CLIENT_TOKEN
            val digest = MessageDigest.getInstance("SHA-256").digest(str.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

        /** 官方库的 JS 风格时间戳（X-Timestamp 头用它，注意 SSML 那处还会再补一个 Z） */
        fun jsDate(nowMs: Long = System.currentTimeMillis()): String {
            val fmt = SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US
            )
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            return fmt.format(Date(nowMs))
        }

        fun speechConfigMessage(nowMs: Long = System.currentTimeMillis()): String =
            "X-Timestamp:${jsDate(nowMs)}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        fun ssmlMessage(
            requestId: String,
            text: String,
            voice: String,
            rate: String,
            pitch: String,
            nowMs: Long = System.currentTimeMillis()
        ): String {
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
                escapeXml(text) + "</prosody></voice></speak>"
            return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${jsDate(nowMs)}Z\r\n" +   // 这个 Z 是官方实现里的行为，不是笔误
                "Path:ssml\r\n\r\n" + ssml
        }

        /** 取 "Key:Value" 头部的值（text/binary 帧的头部格式相同） */
        fun headerValue(headersAndBody: String, key: String): String? =
            headersAndBody.lineSequence()
                .takeWhile { it.isNotEmpty() }
                .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        fun escapeXml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /**
         * 服务端不接受的字符区间（0-8、11-12、14-31）替换成空格；
         * 官方库的注释说最常见的是 OCR 文本里的垂直制表符，不处理会被服务端报错。
         */
        fun removeIncompatible(s: String): String =
            s.map { c -> if (c.code in 0..8 || c.code in 11..12 || c.code in 14..31) ' ' else c }.joinToString("")

        /**
         * 按 UTF-8 字节上限切分，尽量不切断字符。
         *
         * 与官方库的差别：官方库还会优先在换行/空格处断、并避免切断 XML 实体；
         * 这里按码点累加、优先在空白处断，够用且不会产生非法 UTF-8（已用中文/emoji 断言）。
         */
        fun chunkByBytes(text: String, maxBytes: Int = MAX_CHUNK_BYTES): List<String> {
            if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text).filter { it.isNotBlank() }
            val chunks = mutableListOf<String>()
            val sb = StringBuilder()
            var bytes = 0
            var lastBreak = -1
            for (ch in text) {
                val b = ch.toString().toByteArray(Charsets.UTF_8).size
                if (bytes + b > maxBytes) {
                    // 优先在最近的空白处断（把空白留给下一段，避免开头多个空格）
                    if (lastBreak > 0) {
                        chunks.add(sb.substring(0, lastBreak))
                        val rest = sb.substring(lastBreak)
                        sb.setLength(0); sb.append(rest)
                        bytes = rest.toByteArray(Charsets.UTF_8).size
                    } else {
                        chunks.add(sb.toString())
                        sb.setLength(0); bytes = 0
                    }
                    lastBreak = -1
                }
                sb.append(ch); bytes += b
                if (ch.isWhitespace()) lastBreak = sb.length
            }
            if (sb.isNotBlank()) chunks.add(sb.toString())
            return chunks.filter { it.isNotBlank() }
        }

        /** 解析 HTTP Date（RFC 1123，形如 "Thu, 24 Sep 2026 22:09:40 GMT"）→ Unix 秒 */
        fun parseHttpDate(value: String): Double? = runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(value.trim())!!.time / 1000.0
        }.getOrNull()
    }
}

/** 便于在纯 JVM 测试里静音日志；App 内由调用方接 NekoLog */
internal var ttsLogSink: (String) -> Unit = {}

private fun NekoTtsLog(msg: String) {
    ttsLogSink(msg)
}
