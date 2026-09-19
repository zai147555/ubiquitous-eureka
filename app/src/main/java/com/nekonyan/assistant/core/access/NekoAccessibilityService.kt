package com.nekonyan.assistant.core.access

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nekonyan.assistant.core.log.NekoLog

/**
 * 无障碍服务（`猫娘助手.ds` M3：读取界面内容，用于爬楼总结与界面理解）。
 *
 * **只读**是硬边界，写在代码里而不是只写在文档里：
 *   · 只做语义树采集（文本 / contentDescription / 控件类型 / 包名）；
 *   · 不调用 `performAction`，因此**不会**产生任何点击、滑动、文本注入；
 *   · 不做后台常驻抓取：只有界面主动调用 [snapshotCurrentWindow] 时才读一次；
 *   · 每次采集写一条只读日志（日志表本身不可删改）。
 *
 * 需要用户到「系统设置 → 无障碍」里手动开启 —— 这是系统设计，任何应用都无法代开。
 */
class NekoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NekoLog.info(NekoLog.MODULE_PERM, "a11y_connected", "无障碍服务已连接（只读模式）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只记录"最近事件来自哪个包"，不做任何界面操作
        lastEventPackage = event?.packageName?.toString().orEmpty()
        lastEventAt = System.currentTimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        NekoLog.warn(NekoLog.MODULE_PERM, "a11y_unbound", "无障碍服务已关闭")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: NekoAccessibilityService? = null

        @Volatile
        var lastEventPackage: String = ""
            private set

        @Volatile
        var lastEventAt: Long = 0
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * 采集当前窗口的语义树摘要（只读）。
         * 任何失败都返回**人话**而不是抛异常 —— 调用方可能是界面线程。
         */
        fun snapshotCurrentWindow(maxNodes: Int = 120, maxDepth: Int = 12): String {
            val service = instance
                ?: return "无障碍服务未开启：设置 → 无障碍 → 猫娘助手 → 打开"
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
                ?: return "当前窗口读不到内容（可能是系统界面，或窗口未提供语义树）"

            val sb = StringBuilder()
            var count = 0
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || count >= maxNodes || depth > maxDepth) return
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    count++
                    sb.append("  ".repeat(depth))
                        .append(if (text.isNotEmpty()) text else "[$desc]")
                        .append(" <").append(node.className?.toString()?.substringAfterLast('.') ?: "?").append(">\n")
                    if (count >= maxNodes) sb.append("…（已达 $maxNodes 条上限，只取前若干条避免卡顿）\n")
                }
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
            walk(root, 0)

            val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
            NekoLog.info(NekoLog.MODULE_PERM, "a11y_snapshot", "包=$pkg 节点=$count 条（只读）")
            return if (count == 0) "当前窗口没有可读文本（包：$pkg）"
            else "包名：$pkg · 采集 $count 条\n$sb"
        }
    }
}
