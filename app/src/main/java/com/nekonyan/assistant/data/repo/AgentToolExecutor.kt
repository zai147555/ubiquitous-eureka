package com.nekonyan.assistant.data.repo

import android.content.Context
import com.nekonyan.assistant.core.agent.ActionKind
import com.nekonyan.assistant.core.agent.AgentPolicy
import com.nekonyan.assistant.core.agent.ToolCall
import com.nekonyan.assistant.core.agent.ToolRegistry
import com.nekonyan.assistant.core.agent.ToolResult
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.db.AIKnowledgeDao
import com.nekonyan.assistant.data.db.KnowledgeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nekonyan.assistant.core.yolo.NcnnDetector

/**
 * 工具执行器（Agent 循环的手脚）。
 *
 * 三道门**按顺序**过，任一不过就返回一条说明给模型（而不是抛异常）：
 *   ① [ToolRegistry.validate]：未知工具 / 禁用名单（输入注入类）/ 空参数；
 *   ② 动作类确认：`ACT` 类工具在用户点头前不执行；
 *   ③ 具体实现。
 *
 * 已接通：`now`（纯本地）、`kb_search`（读 Room）、`web_fetch`（OkHttp 抓取＋剥标签取正文）、
 * `yolo_detect_local`（NcnnDetector 跑内置模型）、`yolo_detect_service`（YoloServiceClient 发服务端）。
 *
 * 两个如实拒绝的情况（**假成功比失败更难查**：模型会拿空结果继续推理，最后给出看似合理却
 * 毫无依据的答案）：
 *   · `yolo_*` 的 `source=screen`：抓屏服务（M4）没有对外暴露"取一帧"的接口，
 *     所以只能检测**图片文件路径**（`source=file` + `path`），不能假装检测了屏幕；
 *   · `music_play`：播放器句柄由「音乐」页的界面自己持有，工具层拿不到，需要先抽播放服务。
 *
 * `kb_search` 的一个关键点：检索用的是 DAO 的 `itemsForAI()`，
 * 也就是**用户在知识库里关掉 AI 访问的分类，AI 根本读不到** ——
 * 这条需求是在数据层强制的，不依赖提示词里"请不要看"这种君子协定。
 */
class AgentToolExecutor(
    private val context: Context,
    private val knowledgeDao: KnowledgeDao,
    private val aiKnowledgeDao: AIKnowledgeDao,
    /** 用户已确认可执行的动作类工具名（由界面授权后传入） */
    private val confirmedTools: Set<String> = emptySet()
) {

    private val http: okhttp3.OkHttpClient = com.nekonyan.assistant.core.net.DeepSeekClient.defaultClient()
    private val service = com.nekonyan.assistant.core.net.YoloServiceClient()

    @Volatile
    private var detectorReady: Boolean = false

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        // 门①：注册表校验（禁用名单优先于"是否存在"）
        ToolRegistry.validate(call)?.let { reason ->
            NekoLog.warn(NekoLog.MODULE_AI, "tool_rejected", "${ToolRegistry.describe(call)} → $reason")
            return@withContext ToolResult(call.id, call.name, reason, false)
        }
        val tool = ToolRegistry.find(call.name)!!
        if (AgentPolicy.isForbidden(call.name)) {
            return@withContext ToolResult(call.id, call.name, "该工具被策略禁止", false)
        }
        // 门②：动作类必须已确认
        if (tool.kind == ActionKind.ACT && call.name !in confirmedTools) {
            return@withContext ToolResult(
                call.id, call.name,
                "「${call.name}」会改动外界，需要用户确认后才能执行。请先用一句话告诉用户你要做什么，等确认。",
                false
            )
        }
        // 门③：执行
        val started = System.currentTimeMillis()
        val result = runCatching { dispatch(call) }.getOrElse {
            ToolResult(call.id, call.name, "工具执行异常：${it.javaClass.simpleName}: ${it.message}", false)
        }
        NekoLog.info(
            NekoLog.MODULE_AI, "tool_executed",
            "${ToolRegistry.describe(call)} → ${if (result.ok) "成功" else "失败"} " +
                "用时 ${System.currentTimeMillis() - started}ms"
        )
        result
    }

    private suspend fun dispatch(call: ToolCall): ToolResult = when (call.name) {
        "now" -> ToolResult(call.id, call.name, nowText(), true)
        "kb_search" -> kbSearch(call)
        "web_fetch" -> webFetch(call)
        "yolo_detect_local" -> yoloLocal(call)
        "yolo_detect_service" -> yoloService(call)
        "music_play" -> ToolResult(
            call.id, call.name,
            "播放功能不在服务层：音乐页的播放器由界面自己持有，工具层拿不到播放句柄。" +
                "请告诉用户到「音乐」页手动点播，或说明这个能力需要先做播放服务。",
            false
        )
        else -> ToolResult(call.id, call.name, "未知工具：${call.name}", false)
    }

    // ---------------- web_fetch ----------------

    /** 抓网页正文纯文本：剥 script/style → 去标签 → 折叠空白 → 还原常见实体 */
    private suspend fun webFetch(call: ToolCall): ToolResult {
        val a = args(call)
        val url = a.optString("url", "").trim()
        val maxChars = a.optInt("max_chars", 4000).coerceIn(200, 20000)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult(call.id, call.name, "url 必须是 http/https 开头", false)
        }
        return runCatching {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (NekoNyan/1.0)")
                .get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    return ToolResult(call.id, call.name, "抓取失败：HTTP ${r.code}", false)
                }
                val html = r.body?.string().orEmpty()
                val text = stripHtml(html).take(maxChars)
                ToolResult(
                    call.id, call.name,
                    if (text.isBlank()) "页面没有可读正文（可能是纯 JS 渲染的站点）" else text,
                    text.isNotBlank()
                )
            }
        }.getOrElse { ToolResult(call.id, call.name, "抓取异常：${it.javaClass.simpleName}: ${it.message}", false) }
    }

    private fun stripHtml(html: String): String {
        var t = html
        t = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(t, " ")
        t = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(t, " ")
        t = Regex("<[^>]+>").replace(t, " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // ---------------- YOLO ----------------

    /**
     * 本地检测：目前只支持**指定图片文件路径**（source=file）。
     * source=screen 暂不可用 —— M4 的抓屏服务没有对外暴露"取一帧"的接口，
     * 这一点如实说明，而不是假装检测了屏幕。
     */
    private suspend fun yoloLocal(call: ToolCall): ToolResult {
        val a = args(call)
        val source = a.optString("source", "")
        if (source == "screen") {
            return ToolResult(
                call.id, call.name,
                "暂不支持直接检测屏幕：抓屏服务（M4）目前没有对外提供取帧接口。" +
                    "请让用户给出图片路径（source=file + path），或在截屏保存后传入路径。",
                false
            )
        }
        val path = a.optString("path", "").trim()
        if (path.isEmpty()) {
            return ToolResult(call.id, call.name, "需要提供图片路径 path（source=file）", false)
        }
        val file = java.io.File(path)
        if (!file.isFile) return ToolResult(call.id, call.name, "文件不存在：$path", false)

        val bmp = android.graphics.BitmapFactory.decodeFile(path)
            ?: return ToolResult(call.id, call.name, "解码图片失败：$path", false)
        return try {
            ensureDetector()
            val dets = NcnnDetector.detect(bmp)
            if (dets.isEmpty()) {
                ToolResult(call.id, call.name, "未检出目标（阈值 0.3；图 ${bmp.width}x${bmp.height}）", true)
            } else {
                val sb = StringBuilder("检出 ${dets.size} 个目标：\n")
                dets.take(15).forEach { d ->
                    val name = NcnnDetector.labels.getOrNull(d.cls) ?: "cls=${d.cls}"
                    sb.append("- ").append(name)
                        .append(" ").append("%.0f".format(d.conf * 100)).append("%")
                        .append(" 框(").append(d.x1.toInt()).append(',').append(d.y1.toInt())
                        .append('-').append(d.x2.toInt()).append(',').append(d.y2.toInt()).append(")\n")
                }
                ToolResult(call.id, call.name, sb.toString().trim(), true)
            }
        } finally {
            bmp.recycle()
        }
    }

    /** 服务端检测：同样只支持文件路径（图片字节要发出去） */
    private suspend fun yoloService(call: ToolCall): ToolResult {
        val a = args(call)
        val path = a.optString("path", "").trim()
        if (a.optString("source", "") == "screen") {
            return ToolResult(call.id, call.name, "暂不支持屏幕（取帧接口未暴露），请给出图片路径", false)
        }
        if (path.isEmpty()) return ToolResult(call.id, call.name, "需要提供图片路径 path", false)
        val file = java.io.File(path)
        if (!file.isFile) return ToolResult(call.id, call.name, "文件不存在：$path", false)

        val creds = com.nekonyan.assistant.core.security.BuiltinSecretStore.load(context)
            ?: return ToolResult(call.id, call.name, "内置凭据里没有检测服务地址", false)
        val (base, token) = creds
        // 统一压成 JPEG（服务端接受 jpg/png，但统一格式省得判断）
        val bytes = runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return@runCatching null
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it); bmp.recycle() }.toByteArray()
        }.getOrNull() ?: return ToolResult(call.id, call.name, "解码图片失败", false)

        return when (val r = service.detect(base, token, bytes, file.name)) {
            is com.nekonyan.assistant.core.net.ServiceOutcome.Ok -> {
                val d = r.value
                val sb = StringBuilder("服务端检出 ${d.count} 个目标（耗时 ${"%.0f".format(d.totalMs)}ms）：\n")
                d.detections.take(15).forEach { det ->
                    sb.append("- cls=").append(det.cls).append(' ')
                        .append("%.0f".format(det.conf * 100)).append("%")
                        .append(" 框(").append(det.x1.toInt()).append(',').append(det.y1.toInt())
                        .append('-').append(det.x2.toInt()).append(',').append(det.y2.toInt()).append(")\n")
                }
                ToolResult(call.id, call.name, sb.toString().trim(), true)
            }
            is com.nekonyan.assistant.core.net.ServiceOutcome.Err ->
                ToolResult(call.id, call.name, "检测服务返回：${r.message}", false)
        }
    }

    /** 首次用到时加载内置模型（assets 兜底路径由 NcnnDetector 自己处理） */
    private fun ensureDetector() {
        if (detectorReady) return
        detectorReady = NcnnDetector.init(context = context, modelDir = null, useGpu = false, inputSize = 640)
        NekoLog.info(NekoLog.MODULE_YOLO, "agent_detector_init", "ready=$detectorReady 类别=${NcnnDetector.labels.size}")
    }

    // ---------------- now ----------------

    private fun nowText(): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        val tz = java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT)
        return "现在时间：$date $time（$week，时区 $tz）"
    }

    // ---------------- kb_search ----------------

    /**
     * 检索两处：用户知识库（仅 AI 已开启访问的分类）+ AI 知识库。
     * 关键词为空时返回用法提示，而不是把整库倒给模型（那会瞬间吃满上下文）。
     */
    private suspend fun kbSearch(call: ToolCall): ToolResult {
        val a = args(call)
        val q = a.optString("q", "").trim()
        val limit = a.optInt("limit", 5).coerceIn(1, 20)
        if (q.isEmpty()) {
            return ToolResult(call.id, call.name, "缺少检索关键词 q", false)
        }

        val userHits = runCatching {
            knowledgeDao.itemsForAI()
                .filter { it.type == "text" && it.textContent.contains(q, ignoreCase = true) }
                .take(limit)
        }.getOrElse {
            NekoLog.warn(NekoLog.MODULE_AI, "kb_search_failed", it.javaClass.simpleName)
            return ToolResult(call.id, call.name, "读取知识库失败：${it.javaClass.simpleName}", false)
        }

        val aiHits = runCatching {
            aiKnowledgeDao.allItems()
                .filter { it.title.contains(q, ignoreCase = true) || it.summary.contains(q, ignoreCase = true) }
                .take(limit)
        }.getOrDefault(emptyList())

        if (userHits.isEmpty() && aiHits.isEmpty()) {
            return ToolResult(
                call.id, call.name,
                "知识库里没有匹配「$q」的内容（只检索了已开启 AI 访问的分类）", true
            )
        }
        val sb = StringBuilder("知识库检索「$q」：\n")
        userHits.forEachIndexed { i, item ->
            sb.append("${i + 1}. ").append(item.textContent.take(200)).append('\n')
        }
        aiHits.forEachIndexed { i, item ->
            sb.append("AI-${i + 1}. ").append(item.title)
            if (item.summary.isNotBlank()) sb.append("：").append(item.summary.take(200))
            sb.append('\n')
        }
        return ToolResult(call.id, call.name, sb.toString().trim(), true)
    }

    /** 参数解析（org.json）：坏 JSON 不抛异常，按"没给参数"处理 */
    private fun args(call: ToolCall): JSONObject =
        runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
}
