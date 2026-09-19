package com.nekonyan.assistant.core.util

/**
 * 版本号比较（纯 Kotlin，无 Android 依赖 —— 因此可以被普通单元测试直接覆盖）
 *
 * 需求相关：模型版本管理、灰度发布、兼容性检查都依赖"哪个版本更新"。
 *
 * 支持的形态：
 *   "1.2.3" / "v1.2.3" / "V1.2" / "1.10.0"（注意 1.10 > 1.9）
 *   带预发布后缀："2.0.0-rc1" < "2.0.0"
 *   段数不同："1.2" == "1.2.0"
 *   非数字段按 0 处理，绝不抛异常（版本字符串来自服务端，不能信）
 */
object VersionCompare {

    /** a > b 返回正数，a < b 返回负数，相等返回 0 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until n) {
            val na = pa.numbers.getOrElse(i) { 0 }
            val nb = pb.numbers.getOrElse(i) { 0 }
            if (na != nb) return if (na > nb) 1 else -1
        }
        // 数字段相同：预发布版 < 正式版
        return when {
            pa.preRelease == pb.preRelease -> 0
            pa.preRelease -> -1
            else -> 1
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun isAtLeast(actual: String, required: String): Boolean = compare(actual, required) >= 0

    private data class Parsed(val numbers: List<Int>, val preRelease: Boolean)

    private fun parse(v: String): Parsed {
        val trimmed = v.trim().removePrefix("v").removePrefix("V")
        val preRelease = trimmed.contains('-') || trimmed.contains('+')
        val core = trimmed.substringBefore('-').substringBefore('+')
        val numbers = core.split('.').map { seg ->
            // 只取开头的连续数字："3b" → 3；"x" → 0
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return Parsed(numbers, preRelease)
    }
}
