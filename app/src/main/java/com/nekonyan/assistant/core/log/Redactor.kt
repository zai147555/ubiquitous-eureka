package com.nekonyan.assistant.core.log

/**
 * 日志脱敏（需求：API Key、Token、密码、验证码、手机号、身份证自动脱敏）
 *
 * 设计原则：**宁可多脱一点**。日志一旦落盘就是只读的（需求要求不可删除），
 * 写进去的敏感信息再也拿不出来，所以脱敏必须发生在写入之前。
 */
object Redactor {

    private const val MASK = "***"

    /** key=value / key: value / "key": "value" 三种常见形态；统一保留 key、掩掉 value */
    private val kvPatterns: List<Regex> = listOf(
        Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*["']?([A-Za-z0-9_\-]{6,})["']?"""),
        Regex("""(?i)\b(token|access[_-]?token|refresh[_-]?token|session[_-]?key)\s*[:=]\s*["']?([A-Za-z0-9._\-]{6,})["']?"""),
        Regex("""(?i)\b(secret|client[_-]?secret|sign[_-]?secret|hmac)\s*[:=]\s*["']?([A-Za-z0-9._\-]{6,})["']?"""),
        Regex("""(?i)\b(password|passwd|pwd)\s*[:=]\s*["']?(\S{3,})["']?"""),
        Regex("""(?i)\b(code|verify[_-]?code|captcha|sms[_-]?code)\s*[:=]\s*["']?(\d{4,8})["']?""")
    )

    /** Authorization: Bearer xxx / sk-xxx 这类裸凭证 */
    private val bearer = Regex("""(?i)\b(bearer\s+)([A-Za-z0-9._\-]{8,})""")
    private val skKey = Regex("""\bsk-[A-Za-z0-9]{8,}\b""")

    /** 手机号（中国大陆） */
    private val phone = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")
    /** 身份证（18 位，末位可为 X） */
    private val idCard = Regex("""(?<!\d)(\d{17}[\dXx])(?!\d)""")

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var s = input

        kvPatterns.forEach { re ->
            s = re.replace(s) { m ->
                // 保留 key 便于排错，value 一律掩掉
                "${m.groupValues[1]}=$MASK"
            }
        }
        s = bearer.replace(s) { m -> m.groupValues[1] + MASK }
        s = skKey.replace(s, MASK)

        // 证件类：保留首尾各 3 位便于排错，中间全部掩码
        s = phone.replace(s) { m -> maskMiddle(m.value, 3, 4) }
        s = idCard.replace(s) { m -> maskMiddle(m.value, 3, 4) }

        return s
    }

    private fun maskMiddle(v: String, head: Int, tail: Int): String {
        if (v.length <= head + tail) return MASK
        return v.take(head) + "*".repeat(v.length - head - tail) + v.takeLast(tail)
    }
}
