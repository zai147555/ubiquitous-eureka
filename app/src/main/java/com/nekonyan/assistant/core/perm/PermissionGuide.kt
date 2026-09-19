package com.nekonyan.assistant.core.perm

/**
 * 权限引导（`修改.ds` 第五项）的**纯逻辑**部分：清单、顺序、用途文案、进度换算。
 *
 * 刻意把"有哪些权限、各自什么用途、必需与否"放在不依赖 Android 的文件里：
 *   · 需求明确要求"每项说明用途"—— 文案属于产品定义，不该散落在 Activity 里；
 *   · 进度换算（还差几项必需权限）要能被断言，否则会出现"明明都授权了还提示没完成"。
 *
 * Android 侧的实际检测与跳转在 [PermissionChecker]。
 */

enum class PermissionState {
    /** 已授权 */
    GRANTED,
    /** 可申请但当前未授权 */
    DENIED,
    /** 无法用代码申请，只能引导用户手动去系统设置（厂商自启动、后台弹出等） */
    MANUAL,
    /** 设备/系统版本不适用（例如 Android 13 以下没有通知运行时权限） */
    NOT_APPLICABLE
}

data class PermissionItem(
    val key: String,
    val title: String,
    val purpose: String,
    /** 必需项：未授权会直接影响核心功能（聊天通知、悬浮窗、无障碍、录屏、媒体） */
    val required: Boolean
)

object PermissionGuide {

    const val KEY_NOTIFICATION = "notification"
    const val KEY_OVERLAY = "overlay"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_SCREEN_CAPTURE = "screen_capture"
    const val KEY_MEDIA = "media"
    const val KEY_RECORD_AUDIO = "record_audio"
    const val KEY_BATTERY = "battery"
    const val KEY_BACKGROUND_POPUP = "background_popup"
    const val KEY_AUTOSTART = "autostart"

    /** 需求：至少包含这 9 项，顺序即引导顺序（先必需、后可选） */
    val ITEMS: List<PermissionItem> = listOf(
        PermissionItem(
            KEY_NOTIFICATION, "通知",
            "长任务进度、任务完成与出错提醒需要通知权限；不给也能用，只是你收不到提醒。",
            required = true
        ),
        PermissionItem(
            KEY_OVERLAY, "悬浮窗",
            "悬浮窗聊天需要「显示在其他应用上层」；不给则无法在其他 App 上显示聊天窗。",
            required = true
        ),
        PermissionItem(
            KEY_ACCESSIBILITY, "无障碍",
            "读取界面内容（爬楼总结、自动化）需要开启无障碍服务；不给只影响这部分功能。",
            required = true
        ),
        PermissionItem(
            KEY_SCREEN_CAPTURE, "屏幕录制",
            "本地 YOLO 识别画面需要屏幕录制授权。系统不允许预先授权，每次开始识别时都会弹一次确认。",
            required = true
        ),
        PermissionItem(
            KEY_MEDIA, "存储 / 媒体 / 音频",
            "导入音乐与图片需要读取媒体文件；不给则只能读到应用自己目录里的文件。",
            required = true
        ),
        PermissionItem(
            KEY_RECORD_AUDIO, "录音",
            "语音输入需要麦克风权限；不给就只能打字。",
            required = false
        ),
        PermissionItem(
            KEY_BATTERY, "电池优化白名单",
            "长任务期间避免被系统休眠打断；不加白名单，任务可能中途被冻结。",
            required = false
        ),
        PermissionItem(
            KEY_BACKGROUND_POPUP, "后台弹出界面",
            "部分厂商系统（小米/OPPO/vivo 等）需要单独允许后台弹出，否则悬浮窗与提醒会被拦。",
            required = false
        ),
        PermissionItem(
            KEY_AUTOSTART, "自启动",
            "可选：开机后自动恢复上次的长任务。",
            required = false
        )
    )

    data class Progress(
        val grantedRequired: Int,
        val totalRequired: Int,
        val grantedAll: Int,
        val total: Int
    ) {
        val requiredDone: Boolean get() = grantedRequired >= totalRequired

        val percent: Int get() = if (total == 0) 100 else grantedAll * 100 / total

        val summary: String
            get() = if (requiredDone) "已授权 $grantedAll/$total（必需项已完成）"
            else "已授权 $grantedAll/$total（还差 ${totalRequired - grantedRequired} 项必需权限）"
    }

    /** MANUAL 视为"已按引导处理过"？不 —— 只有真正 GRANTED 才算授权，避免自欺欺人 */
    fun isGranted(state: PermissionState?): Boolean = state == PermissionState.GRANTED

    fun progress(states: Map<String, PermissionState>): Progress {
        val required = ITEMS.filter { it.required }
        return Progress(
            grantedRequired = required.count { isGranted(states[it.key]) },
            totalRequired = required.size,
            grantedAll = ITEMS.count { isGranted(states[it.key]) },
            total = ITEMS.size
        )
    }

    /** 需求：未授权时相关功能降级并提示（界面直接显示这句话，不弹崩溃） */
    fun degradationHint(key: String): String = when (key) {
        KEY_NOTIFICATION -> "未授权通知：任务完成与报错只在应用内提示"
        KEY_OVERLAY -> "未授权悬浮窗：悬浮窗聊天不可用，其他功能不受影响"
        KEY_ACCESSIBILITY -> "未开启无障碍：爬楼总结与界面读取类功能不可用"
        KEY_SCREEN_CAPTURE -> "未授权屏幕录制：本地识别无法开始（每次都要现场确认）"
        KEY_MEDIA -> "未授权媒体：只能读取应用自己目录中的音乐与图片"
        KEY_RECORD_AUDIO -> "未授权录音：语音输入不可用，可用文字输入"
        KEY_BATTERY -> "未加入电池白名单：长任务可能被系统冻结"
        KEY_BACKGROUND_POPUP -> "未允许后台弹出：悬浮窗与提醒可能被厂商系统拦截"
        KEY_AUTOSTART -> "未开启自启动：开机后需要手动恢复任务"
        else -> "该权限未授权，相关功能会自动降级"
    }
}
