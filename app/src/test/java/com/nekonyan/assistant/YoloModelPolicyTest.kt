package com.nekonyan.assistant

import com.nekonyan.assistant.core.yolo.ModelFiles
import com.nekonyan.assistant.core.yolo.ModelPolicy
import com.nekonyan.assistant.core.yolo.SignatureCheck
import com.nekonyan.assistant.core.yolo.SwitchContext
import com.nekonyan.assistant.core.yolo.SwitchGuard
import com.nekonyan.assistant.core.yolo.VersionInfo
import com.nekonyan.assistant.core.yolo.VersionRetention
import com.nekonyan.assistant.core.yolo.YoloScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YOLO 模型策略的纯逻辑测试（对应工作区 `yolo.ds`）。
 *
 * 选这几块钉死，理由都是"错了很难在真机上定位"：
 *   · 场景→模型对照表 —— 错一行就是"该用 11n 的时候用了没装的 8n"，表现为莫名降级；
 *   · 切换前检查 —— 不拦会写出半个模型；拦过头会让用户以为按钮坏了；
 *   · 版本保留 —— 算错就是把当前模型删掉，App 直接没模型可用；
 *   · 文件名解析 —— NCNN 要求 .param/.bin 同名，认错就是导入成功但加载不了。
 */
class YoloModelPolicyTest {

    // ==================== 场景 → 模型（yolo.ds 第 47~54 行） ====================

    @Test
    fun `场景对照表与需求一致`() {
        assertEquals(ModelPolicy.V11N, ModelPolicy.preferredModel(YoloScene.NORMAL))
        assertEquals(ModelPolicy.V8N, ModelPolicy.preferredModel(YoloScene.COMBAT))
        assertEquals(ModelPolicy.V11N, ModelPolicy.preferredModel(YoloScene.LONG_TASK))
        assertEquals(ModelPolicy.V11N, ModelPolicy.preferredModel(YoloScene.LOW_POWER))
        assertEquals(ModelPolicy.V5N, ModelPolicy.preferredModel(YoloScene.SPEED))
        assertEquals(ModelPolicy.V9, ModelPolicy.preferredModel(YoloScene.HIGH_ACCURACY))
    }

    @Test
    fun `场景键解析非法值回退普通`() {
        assertEquals(YoloScene.NORMAL, YoloScene.fromKey(null))
        assertEquals(YoloScene.NORMAL, YoloScene.fromKey("不存在的场景"))
        assertEquals(YoloScene.COMBAT, YoloScene.fromKey("combat"))
    }

    @Test
    fun `装了场景模型就用它`() {
        val installed = setOf(ModelPolicy.V11N, ModelPolicy.V8N)
        assertEquals(ModelPolicy.V8N, ModelPolicy.resolve(YoloScene.COMBAT, installed, ModelPolicy.V11N))
    }

    @Test
    fun `场景模型没装则沿用当前`() {
        val installed = setOf(ModelPolicy.V11N)
        assertEquals(ModelPolicy.V11N, ModelPolicy.resolve(YoloScene.COMBAT, installed, ModelPolicy.V11N))
    }

    @Test
    fun `都没有时回退内置而非报错`() {
        assertEquals(ModelPolicy.V11N, ModelPolicy.resolve(YoloScene.COMBAT, emptySet(), null))
        assertEquals(ModelPolicy.V11N, ModelPolicy.resolve(YoloScene.NORMAL, emptySet(), null))
    }

    @Test
    fun `结果确定不随集合顺序变化`() {
        val a = ModelPolicy.resolve(YoloScene.COMBAT, setOf("aaa-custom", "zzz-custom"), null)
        val b = ModelPolicy.resolve(YoloScene.COMBAT, setOf("zzz-custom", "aaa-custom"), null)
        assertEquals(a, b)
    }

    // ==================== 切换前检查（yolo.ds 第 55 行） ====================

    private fun ctx(
        free: Long = 10L * 1024 * 1024 * 1024,
        need: Long = 5L * 1024 * 1024,
        battery: Int = 80,
        temp: Double = 35.0,
        cooldown: Long = 0
    ) = SwitchContext(free, need, battery, temp, cooldown)

    @Test
    fun `条件充足时放行`() {
        assertTrue(SwitchGuard.check(ctx()).allowed)
        assertNull(SwitchGuard.check(ctx()).reason)
    }

    @Test
    fun `存储不足被拦下且说明差多少`() {
        val r = SwitchGuard.check(ctx(free = 6L * 1024 * 1024, need = 5L * 1024 * 1024))
        assertFalse(r.allowed)
        assertTrue("原因应说明存储：${r.reason}", r.reason!!.contains("存储不足"))
        assertTrue(r.reason!!.contains("MB"))
    }

    @Test
    fun `电量过低被拦下`() {
        val r = SwitchGuard.check(ctx(battery = 5))
        assertFalse(r.allowed)
        assertTrue(r.reason!!.contains("电量过低"))
    }

    @Test
    fun `温度过高被拦下`() {
        val r = SwitchGuard.check(ctx(temp = 47.5))
        assertFalse(r.allowed)
        assertTrue(r.reason!!.contains("温度过高"))
    }

    @Test
    fun `冷却中给出剩余秒数`() {
        val r = SwitchGuard.check(ctx(cooldown = 1500))
        assertFalse(r.allowed)
        assertTrue("应四舍五入到 2 秒：${r.reason}", r.reason!!.contains("2 秒"))
    }

    @Test
    fun `电量边界是十个百分点`() {
        // 恰好 10% 放行、9% 拦截：边界写错的表现是"电量告警时模型切不过去"，很难复现
        assertTrue(SwitchGuard.check(ctx(battery = 10)).allowed)
        assertFalse(SwitchGuard.check(ctx(battery = 9)).allowed)
    }

    @Test
    fun `人话大小换算`() {
        assertEquals("512 B", SwitchGuard.humanSize(512))
        assertEquals("1 KB", SwitchGuard.humanSize(1024))
        assertEquals("5.0 MB", SwitchGuard.humanSize(5L * 1024 * 1024))
        assertEquals("1.5 GB", SwitchGuard.humanSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    // ==================== 版本保留（yolo.ds 第 67/75/76 行） ====================

    @Test
    fun `保留数被夹在 2 到 5`() {
        assertEquals(2, VersionRetention.clampKeep(0))
        assertEquals(2, VersionRetention.clampKeep(1))
        assertEquals(5, VersionRetention.clampKeep(99))
        assertEquals(3, VersionRetention.clampKeep(3))
    }

    @Test
    fun `只清理超出的旧版本`() {
        val versions = listOf(
            VersionInfo("v1", "1.0", 100),
            VersionInfo("v2", "1.1", 200),
            VersionInfo("v3", "1.2", 300)
        )
        assertEquals(listOf("v1"), VersionRetention.prunePlan(versions, 2))
    }

    @Test
    fun `当前版本无论多旧都不删`() {
        val versions = listOf(
            VersionInfo("old-current", "0.9", 10, isCurrent = true),
            VersionInfo("new1", "1.1", 300),
            VersionInfo("new2", "1.2", 400),
            VersionInfo("new3", "1.3", 500)
        )
        // 保留最新 2 个（new3/new2）+ 当前（old-current）→ 只清 new1
        val plan = VersionRetention.prunePlan(versions, 2)
        assertFalse("当前版本不能被清理：$plan", plan.contains("old-current"))
        assertEquals(listOf("new1"), plan)
    }

    @Test
    fun `全部保留时无需清理`() {
        val versions = listOf(VersionInfo("a", "1", 1), VersionInfo("b", "2", 2))
        assertTrue(VersionRetention.prunePlan(versions, 5).isEmpty())
    }

    // ==================== 签名（yolo.ds 第 29/34/64 行） ====================

    @Test
    fun `未声明期望签名时不拦`() {
        assertTrue(SignatureCheck.matches(null, "abc"))
        assertTrue(SignatureCheck.matches("", "abc"))
    }

    @Test
    fun `声明了就必须一致且忽略前缀大小写`() {
        val sha = "a".repeat(64)
        assertTrue(SignatureCheck.matches("SHA256:${sha.uppercase()}", sha))
        assertFalse(SignatureCheck.matches(sha, "b".repeat(64)))
        assertFalse(SignatureCheck.matches(sha, null))
    }

    @Test
    fun `识别 sha256 十六进制串`() {
        assertTrue(SignatureCheck.isHex64("0123456789abcdef".repeat(4)))
        assertFalse(SignatureCheck.isHex64("xyz"))
        assertFalse(SignatureCheck.isHex64(null))
    }

    // ==================== 文件解析（yolo.ds 第 33 行） ====================

    @Test
    fun `家族识别覆盖四种模型`() {
        assertEquals(ModelPolicy.V11N, ModelFiles.familyOf("yolo11n.param"))
        assertEquals(ModelPolicy.V11N, ModelFiles.familyOf("yolov11n.bin"))
        assertEquals(ModelPolicy.V8N, ModelFiles.familyOf("yolov8n.param"))
        assertEquals(ModelPolicy.V5N, ModelFiles.familyOf("yolov5n.bin"))
        assertEquals(ModelPolicy.V9, ModelFiles.familyOf("yolov9c.param"))
        assertNull(ModelFiles.familyOf("mobilenet.param"))
    }

    @Test
    fun `param 与 bin 必须同基名`() {
        assertTrue(ModelFiles.pairsWith("yolo11n.param", "yolo11n.bin"))
        assertTrue(ModelFiles.pairsWith("YOLO11N.PARAM", "yolo11n.bin"))
        assertFalse(ModelFiles.pairsWith("yolo11n.param", "yolov8n.bin"))
    }

    @Test
    fun `从 param 首层读输入尺寸`() {
        val param = """
            7767517
            235 240
            Input            images                   0 1 images 0=1 1=3 2=640 3=640
            Convolution      conv_0                   1 1 images conv_0 0=16 1=3 5=1 6=1728
        """.trimIndent()
        assertEquals(640, ModelFiles.detectInputSize(param))
        assertNull("读不到时应返回 null，让调用方回退而不是猜", ModelFiles.detectInputSize("7767517\n1 1\n"))
        assertEquals(416, ModelFiles.detectInputSize("Input in 0 1 in 0=1 1=3 2=416 3=416"))
    }

    @Test
    fun `类别数按非空非注释行统计`() {
        val labels = "person\ncat\n\n# 注释\n dog \n"
        assertEquals(3, ModelFiles.countLabels(labels))
        assertEquals(0, ModelFiles.countLabels(""))
    }
}
