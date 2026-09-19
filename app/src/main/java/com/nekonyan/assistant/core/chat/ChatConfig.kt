package com.nekonyan.assistant.core.chat

/**
 * 对话配置（**纯 Kotlin**，可在本机 kotlinc 下独立编译并断言）。
 *
 * 为什么单独抽成不依赖 Android 的文件：
 *   「拦住错误配置」是发请求之前唯一能做的事 —— 地址写成 http、Key 忘填、
 *   超时填成 0 这三类错误如果放到网络层才发现，用户看到的只会是超时或 401，
 *   排查成本极高。因此这里把所有归一化与校验做成可真实运行测试的纯逻辑。
 *
 * 安全：需求要求「全链路 HTTPS + Key 不明文上网」，所以非 https 一律拒绝，
 *       [describeForLog] 保证 Key 永远不进日志（只输出长度与是否已配置）。
 */
data class ChatConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS
) {

    /** 归一化 + 夹取；存盘与发请求都必须先过这里 */
    fun normalized(): ChatConfig = copy(
        baseUrl = normalizeBaseUrl(baseUrl),
        apiKey = apiKey.trim(),
        model = normalizeModel(model),
        timeoutSeconds = clampTimeout(timeoutSeconds),
        temperature = clampTemperature(temperature),
        maxTokens = clampMaxTokens(maxTokens)
    )

    /** 完整请求地址（OpenAI 兼容） */
    fun endpoint(): String = chatCompletionsUrl(baseUrl)

    /**
     * 能否发起对话。返回 `null` 表示可以；否则返回**给用户看的原因**。
     * 刻意返回文案而不是布尔：界面可以直接显示，不用再翻译一次。
     */
    fun blockingProblem(): String? {
        val c = normalized()
        if (c.baseUrl.isBlank()) return "API 地址为空，请填写 https://api.deepseek.com"
        if (!isSecureBase(c.baseUrl)) return "API 地址必须是 https:// 开头（http 会把 API Key 明文发到网络上）"
        if (!isValidHost(c.baseUrl)) return "API 地址格式不对，应形如 https://api.deepseek.com"
        if (c.apiKey.isBlank()) return "还没有填 DeepSeek API Key（侧边菜单 → 配置）"
        if (c.model.isBlank()) return "模型名不能为空"
        return null
    }

    /** 日志用描述：**永不包含 Key 明文** */
    fun describeForLog(): String =
        "model=${normalizeModel(model)}, base=${normalizeBaseUrl(baseUrl)}, " +
            "timeout=${clampTimeout(timeoutSeconds)}s, maxTokens=${clampMaxTokens(maxTokens)}, " +
            "key=${if (apiKey.isBlank()) "未配置" else "已配置(len=${apiKey.trim().length})"}"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val CHAT_PATH = "/chat/completions"

        /** 需求：云端为 DeepSeek，OpenAI 兼容（deepseek-chat / deepseek-vl） */
        val KNOWN_MODELS = listOf("deepseek-chat", "deepseek-reasoner", "deepseek-vl")

        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 300

        const val DEFAULT_TEMPERATURE = 0.7
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0

        const val DEFAULT_MAX_TOKENS = 2048
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 8192

        /** 需求：全链路 HTTPS */
        fun isSecureBase(url: String): Boolean =
            url.trim().lowercase().startsWith("https://")

        /** 至少要有一个主机名（拦住 "https://" 这种半截地址） */
        fun isValidHost(url: String): Boolean {
            val rest = url.trim().removePrefix("https://").removePrefix("http://")
            val host = rest.substringBefore('/').substringBefore('?')
            return host == "localhost" || (host.isNotBlank() && host.contains('.'))
        }

        /**
         * 归一化地址：去空白、去尾部斜杠、容忍用户把完整 endpoint 粘进来。
         * 例：`https://api.deepseek.com/v1/chat/completions/` → `https://api.deepseek.com/v1`
         */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return DEFAULT_BASE_URL
            u = u.trimEnd('/')
            u = u.removeSuffix(CHAT_PATH)
            return u.trimEnd('/')
        }

        fun chatCompletionsUrl(baseUrl: String): String =
            normalizeBaseUrl(baseUrl) + CHAT_PATH

        fun normalizeModel(raw: String): String = raw.trim().ifEmpty { DEFAULT_MODEL }

        fun clampTimeout(v: Int): Int = v.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

        fun clampMaxTokens(v: Int): Int = v.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)

        fun clampTemperature(v: Double): Double =
            if (v.isNaN()) DEFAULT_TEMPERATURE else v.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
    }
}
