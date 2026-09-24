package com.nekonyan.assistant.core.agent

/**
 * 工具注册表（**纯 Kotlin**：只有定义与校验，具体执行在各平台的 executor 里）。
 *
 * 第一批六个工具，都是"读为主、最多一步动作"的：
 *   · `kb_search`           本机知识库检索（含 AI 知识库）
 *   · `yolo_detect_local`   内置 YOLOv11n 对一张图做本地检测（NCNN，约 234ms/帧）
 *   · `yolo_detect_service` 调自建检测服务（HTTPS，返回原图坐标）
 *   · `web_fetch`           抓网页正文纯文本（给"视频/文章总结"用）
 *   · `now`                 当前时间（模型没有时钟，很多任务要用）
 *   · `music_play`          播放本地音乐（唯一一个 ACT 类：会改动外界 → 需用户确认）
 *
 * **刻意没有的**：任何输入注入类工具（点击/滑动/按键注入、操作别的应用）。
 * 它们不在 [ActionKind] 里、也不在 [AgentPolicy.FORBIDDEN_TOOLS] 之外 —— 两层都拦得住。
 */
object ToolRegistry {

    private const val OBJ = "\"type\":\"object\""

    val definitions: List<AgentTool> = listOf(
        AgentTool(
            name = "kb_search",
            description = "在本机知识库里检索条目。需要用户问到自己存过的资料、笔记、设定时使用。",
            parametersJson = "{$OBJ,\"properties\":{\"q\":{\"type\":\"string\",\"description\":\"检索关键词\"},\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，默认 5\"}},\"required\":[\"q\"]}"
        ),
        AgentTool(
            name = "yolo_detect_local",
            description = "用手机内置的 YOLOv11n 模型对一张图片或当前屏幕截图做本地目标检测（COCO 80 类），返回框与类别。",
            parametersJson = "{$OBJ,\"properties\":{\"source\":{\"type\":\"string\",\"enum\":[\"screen\",\"file\"],\"description\":\"screen=当前屏幕，file=指定图片\"},\"conf\":{\"type\":\"number\",\"description\":\"置信度阈值，默认 0.3\"}},\"required\":[\"source\"]}"
        ),
        AgentTool(
            name = "yolo_detect_service",
            description = "把一张图发到自建的 YOLO11n 检测服务做识别，返回原图坐标系下的检测框。本地模型不便处理时可选用。",
            parametersJson = "{$OBJ,\"properties\":{\"source\":{\"type\":\"string\",\"enum\":[\"screen\",\"file\"]}},\"required\":[\"source\"]}"
        ),
        AgentTool(
            name = "web_fetch",
            description = "抓取一个网页并返回正文纯文本（用于总结文章/视频页信息）。",
            parametersJson = "{$OBJ,\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"http/https 地址\"},\"max_chars\":{\"type\":\"integer\",\"description\":\"最多返回多少字符，默认 4000\"}},\"required\":[\"url\"]}"
        ),
        AgentTool(
            name = "now",
            description = "获取当前日期与时间（含星期）。需要判断「今天/现在」时使用。",
            parametersJson = "{$OBJ,\"properties\":{}}"
        ),
        AgentTool(
            name = "music_play",
            description = "播放本机音乐库中的一首歌（按歌名模糊匹配）。会发出声音，属于动作类操作。",
            parametersJson = "{$OBJ,\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"歌名或文件名关键词\"}},\"required\":[\"title\"]}",
            kind = ActionKind.ACT
        )
    )

    fun find(name: String): AgentTool? = definitions.firstOrNull { it.name == name }

    /** 这个工具是否必须用户点头才能执行 */
    fun needsConfirm(name: String): Boolean = find(name)?.needsConfirm == true

    /**
     * 校验一次调用是否可执行。返回 null 表示通过，否则是给日志/模型看的原因。
     * 注意顺序：**先查禁用名单**，再查是否存在 —— 万一有人手滑注册了同名注入工具，
     * 禁用判断也必须先生效。
     */
    fun validate(call: ToolCall): String? = when {
        AgentPolicy.isForbidden(call.name) -> "该工具属于输入注入类，被策略禁止：${call.name}"
        find(call.name) == null -> "未知工具：${call.name}"
        call.argumentsJson.isBlank() -> "缺少参数（arguments 为空）"
        else -> null
    }

    /** 给日志用的一行摘要（不打印参数全文，避免把用户内容刷进日志） */
    fun describe(call: ToolCall): String =
        "${call.name}(${call.argumentsJson.length} 字符参数)"
}
