package com.nekonyan.assistant

import com.nekonyan.assistant.core.log.Redactor
import com.nekonyan.assistant.core.util.SigningCanonical
import com.nekonyan.assistant.core.util.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 纯 Kotlin 核心算法的单元测试（不依赖 Android 框架，因此跑得飞快）。
 *
 * 这三块是"写错了很难在真机上定位"的地方：
 *   · 版本比较 —— 错一位就导致模型更新/灰度判断出错；
 *   · 签名规范化串 —— 错一个字符就全线 401；
 *   · 日志脱敏 —— 一旦落盘就再也拿不出来（日志只读）。
 */
class CoreUtilsTest {

    // ==================== 版本比较 ====================

    @Test
    fun `数值段按数字比较而不是字符串比较`() {
        assertTrue("1.9 应小于 1.10", VersionCompare.compare("1.9.0", "1.10.0") < 0)
        assertTrue("2.0 应大于 1.99.99", VersionCompare.compare("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `前缀 v 与大写 V 都可忽略`() {
        assertEquals(0, VersionCompare.compare("v2.0.1", "2.0.1"))
        assertEquals(0, VersionCompare.compare("V2.0.1", "v2.0.1"))
    }

    @Test
    fun `段数不同时缺失段按 0 处理`() {
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0"))
        assertTrue(VersionCompare.compare("1.2.1", "1.2") > 0)
    }

    @Test
    fun `预发布版小于同号正式版`() {
        assertTrue(VersionCompare.compare("2.0.0-rc1", "2.0.0") < 0)
        assertTrue(VersionCompare.compare("2.0.0", "2.0.0-rc1") > 0)
        assertEquals(0, VersionCompare.compare("2.0.0-rc1", "2.0.0-beta"))
    }

    @Test
    fun `非法与空版本串不抛异常`() {
        VersionCompare.compare("abc", "1.0")
        VersionCompare.compare("", "")
        VersionCompare.compare("...", "1")
        VersionCompare.compare("v", "0")
        assertTrue(true)   // 能走到这里就说明没抛
    }

    @Test
    fun `min_app_version 兼容性检查`() {
        assertTrue("0.2.0 满足 0.1.5", VersionCompare.isAtLeast("0.2.0", "0.1.5"))
        assertFalse("0.1.0 不满足 0.1.5", VersionCompare.isAtLeast("0.1.0", "0.1.5"))
        assertTrue("相等也算满足", VersionCompare.isAtLeast("0.1.5", "0.1.5"))
    }

    @Test
    fun `isNewer 判断更新`() {
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("0.9.9", "1.0.0"))
    }

    // ==================== 签名规范化串 ====================

    @Test
    fun `空体 sha256 是标准值`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0)).joinToString("") { "%02x".format(it) }
        assertEquals(expected, SigningCanonical.EMPTY_BODY_SHA256)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SigningCanonical.EMPTY_BODY_SHA256
        )
    }

    @Test
    fun `query 按 key 升序排列`() {
        val q = SigningCanonical.canonicalQuery(
            listOf(
                "platform" to "android", "device_id" to "abc",
                "abi" to "arm64-v8a", "current" to "v3", "app_version" to "0.2.0"
            )
        )
        assertEquals(
            "abi=arm64-v8a&app_version=0.2.0&current=v3&device_id=abc&platform=android",
            q
        )
    }

    @Test
    fun `同 key 时按 value 升序`() {
        assertEquals(
            "a=1&a=2&b=2",
            SigningCanonical.canonicalQuery(listOf("b" to "2", "a" to "2", "a" to "1"))
        )
    }

    @Test
    fun `无 query 时为空串而不是 null 文本`() {
        assertEquals("", SigningCanonical.canonicalQuery(emptyList()))
    }

    @Test
    fun `空值参数写成 key 等号形式`() {
        assertEquals("x=", SigningCanonical.canonicalQuery(listOf("x" to "")))
    }

    @Test
    fun `canonical 恰好是六段以换行连接`() {
        val c = SigningCanonical.canonical(
            "GET", "/model/latest", "a=1", "1789300000000", "n1",
            SigningCanonical.EMPTY_BODY_SHA256
        )
        assertEquals(5, c.count { it == '\n' })
        assertEquals(
            listOf("GET", "/model/latest", "a=1", "1789300000000", "n1",
                SigningCanonical.EMPTY_BODY_SHA256),
            c.split("\n")
        )
    }

    @Test
    fun `篡改 device_id 会改变 canonical（防灰度分桶被中间人操纵）`() {
        val a = SigningCanonical.canonicalQuery(listOf("device_id" to "abc", "current" to "v3"))
        val b = SigningCanonical.canonicalQuery(listOf("device_id" to "EVIL", "current" to "v3"))
        assertNotEquals(a, b)

        val ca = SigningCanonical.canonical("GET", "/model/latest", a, "1", "n",
            SigningCanonical.EMPTY_BODY_SHA256)
        val cb = SigningCanonical.canonical("GET", "/model/latest", b, "1", "n",
            SigningCanonical.EMPTY_BODY_SHA256)
        assertNotEquals(ca, cb)
    }

    @Test
    fun `PATH 不含 query（与服务端约定一致）`() {
        val c = SigningCanonical.canonical(
            "GET", "/model/latest", "a=1", "1", "n", SigningCanonical.EMPTY_BODY_SHA256
        )
        val pathSeg = c.split("\n")[1]
        assertFalse("PATH 段不应含问号", pathSeg.contains("?"))
        assertEquals("/model/latest", pathSeg)
    }

    // ==================== 日志脱敏 ====================

    @Test
    fun `API Key 与 Token 被掩码`() {
        assertFalse(Redactor.redact("api_key=sk-abcdef1234567890").contains("sk-abcdef1234567890"))
        assertFalse(Redactor.redact("token: abcdef123456").contains("abcdef123456"))
        assertFalse(
            Redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9")
                .contains("eyJhbGciOiJIUzI1NiJ9")
        )
        assertFalse(Redactor.redact("sign_secret=mysupersecret123").contains("mysupersecret123"))
    }

    @Test
    fun `密码与验证码被掩码`() {
        assertFalse(Redactor.redact("password=hunter2xyz").contains("hunter2xyz"))
        assertFalse(Redactor.redact("verify_code=8823").contains("8823"))
    }

    @Test
    fun `手机号与身份证保留首尾便于排错`() {
        val phone = Redactor.redact("用户 13812345678 来电")
        assertFalse(phone.contains("13812345678"))
        assertTrue("应保留前 3 位", phone.contains("138"))

        val id = Redactor.redact("身份证 11010119900307123X")
        assertFalse(id.contains("11010119900307123X"))
    }

    @Test
    fun `裸 sk- 开头的密钥被掩码`() {
        assertFalse(Redactor.redact("key=sk-proj1234567890abcdef").contains("sk-proj1234567890abcdef"))
    }

    @Test
    fun `普通日志内容不被误伤`() {
        assertEquals("检测到 3 个目标，耗时 87ms", Redactor.redact("检测到 3 个目标，耗时 87ms"))
        assertEquals("model_version=v4", Redactor.redact("model_version=v4"))
        assertEquals("", Redactor.redact(""))
    }
}
