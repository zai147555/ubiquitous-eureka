package com.nekonyan.assistant

import com.nekonyan.assistant.core.collect.CollectPolicy
import com.nekonyan.assistant.core.collect.CollectPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集判定的单元测试。
 *
 * 这段逻辑决定"什么时候把用户屏幕内容传出去"，坏掉的方式是隐私与流量事故：
 * 关不掉、间隔失效（2 帧/秒 ≈ 1.4GB/小时）、上限失效、去重失效（传一堆几乎相同的帧）。
 */
class CollectPolicyTest {

    private val on = CollectPolicy.Rules(enabled = true)
    private fun usage(count: Int = 0, bytes: Long = 0, last: Long = 0, hashes: List<Long> = emptyList()) =
        CollectPolicy.Usage(todayCount = count, todayBytes = bytes, lastCaptureAtMs = last, recentHashes = hashes)

    @Test
    fun disabled_means_never_capture() {
        val off = on.copy(enabled = false)
        assertTrue(CollectPolicy.shouldCapture(off, usage(), 100_000, 0L) is Decision.Skip)
        assertTrue(CollectPolicy.shouldUploadNow(off, onWifi = true) is Decision.Skip)
    }

    @Test
    fun interval_is_enforced_and_has_a_floor() {
        // 刚采过 → 跳过
        assertTrue(CollectPolicy.shouldCapture(on, usage(last = 1_000_000), 1_010_000, 0L) is Decision.Skip)
        // 超过间隔 → 采
        assertTrue(CollectPolicy.shouldCapture(on, usage(last = 1_000_000), 1_031_000, 0L) is Decision.Capture)
        // 有人把间隔配成 100ms 也得按 5 秒下限走（否则就是 1.4GB/小时的来源）
        val fast = on.copy(intervalMs = 100L)
        assertTrue(CollectPolicy.shouldCapture(fast, usage(last = 1_000_000), 1_001_000, 0L) is Decision.Skip)
        assertTrue(CollectPolicy.shouldCapture(fast, usage(last = 1_000_000), 1_006_000, 0L) is Decision.Capture)
    }

    @Test
    fun daily_caps_stop_uploading_no_matter_what() {
        val manyFrames = usage(count = on.dailyCapCount, last = 0)
        assertTrue(CollectPolicy.shouldCapture(on, manyFrames, 9_999_999, 0L) is Decision.Skip)
        val bigBytes = usage(bytes = on.dailyCapBytes)
        assertTrue(CollectPolicy.shouldCapture(on, bigBytes, 9_999_999, 0L) is Decision.Skip)
    }

    @Test
    fun wifi_only_holds_upload_but_not_capture() {
        // 仅 Wi-Fi：移动网络下不入队？—— 入队照旧（等 Wi-Fi 再传），只是不马上传
        assertTrue(CollectPolicy.shouldCapture(on, usage(), 9_999_999, 0L) is Decision.Capture)
        assertTrue(CollectPolicy.shouldUploadNow(on, onWifi = false) is Decision.Skip)
        assertTrue(CollectPolicy.shouldUploadNow(on, onWifi = true) is Decision.Capture)
        // 关掉"仅 Wi-Fi"后移动网络也传
        assertTrue(CollectPolicy.shouldUploadNow(on.copy(wifiOnly = false), onWifi = false) is Decision.Capture)
    }

    @Test
    fun duplicate_frames_are_skipped_but_different_ones_pass() {
        val h = 0b1010_1010_1010_1010L
        val almostSame = h xor 0b11L          // 差 2 位 → 视为同一画面
        val different = h.inv()               // 差 64 位
        assertTrue(CollectPolicy.shouldCapture(on, usage(hashes = listOf(h)), 9_999_999, almostSame) is Decision.Skip)
        assertTrue(CollectPolicy.shouldCapture(on, usage(hashes = listOf(h)), 9_999_999, different) is Decision.Capture)
        // 关掉去重就都采
        assertTrue(
            CollectPolicy.shouldCapture(on.copy(skipDuplicates = false), usage(hashes = listOf(h)), 9_999_999, h)
                is Decision.Capture
        )
    }

    @Test
    fun dhash_is_stable_and_direction_sensitive() {
        // 4x1 亮度：递增 → 每位都是"不大于" → 全 0
        assertEquals(0L, CollectPolicy.dHash(intArrayOf(10, 20, 30, 40), 4, 1))
        // 递减 → 每位都是"大于" → 低 3 位全 1
        assertEquals(0b111L, CollectPolicy.dHash(intArrayOf(40, 30, 20, 10), 4, 1))
        // 同样输入必须得到同样结果（否则去重形同虚设）
        val a = CollectPolicy.dHash(intArrayOf(5, 9, 2, 8, 1, 7), 3, 2)
        val b = CollectPolicy.dHash(intArrayOf(5, 9, 2, 8, 1, 7), 3, 2)
        assertEquals(a, b)
        // 尺寸非法时返回 0 而不是崩
        assertEquals(0L, CollectPolicy.dHash(intArrayOf(1), 1, 1))
        assertEquals(0L, CollectPolicy.dHash(intArrayOf(), 0, 0))
    }

    @Test
    fun hamming_and_trim() {
        assertEquals(0, CollectPolicy.hamming(7L, 7L))
        // 7 xor 8 = 15 = 0b1111 → 4 位不同（我第一版手算成 3 位，被断言抓出来）
        assertEquals(4, CollectPolicy.hamming(0b111L, 0b1000L))
        assertEquals(64, CollectPolicy.hamming(0L, -1L))
        // 只留最近 8 个（避免偏好无限增长）
        val many = (1L..20L).toList()
        val trimmed = CollectPolicy.trimHashes(many)
        assertEquals(8, trimmed.size)
        assertEquals(many.takeLast(8), trimmed)
        assertFalse(trimmed.contains(1L))
    }
}
