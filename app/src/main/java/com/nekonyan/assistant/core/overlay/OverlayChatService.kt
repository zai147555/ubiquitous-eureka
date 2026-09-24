package com.nekonyan.assistant.core.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.R
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.data.repo.AppearanceSettings
import com.nekonyan.assistant.data.repo.ThemeStore
import com.nekonyan.assistant.ui.screen.ChatViewModel
import com.nekonyan.assistant.ui.theme.NekoTheme

/**
 * 悬浮窗聊天（M3）：在其他 App 上层显示一个可拖动气泡，点开是迷你聊天窗。
 *
 * 几个必须踩对、否则真机上会莫名失效的点：
 *
 *   ① **前台服务**：Android 12+ 后台服务随时被杀，气泡会"自己消失"。
 *      用 specialUse 类型（API 34+ 必须声明类型，manifest 里配了 subtype 属性）；
 *   ② **Compose 挂到系统窗口需要三个 ViewTree owner**：Lifecycle / ViewModelStore /
 *      SavedStateRegistry。缺 LifecycleOwner 时 ComposeView 会在拿 Recomposer 时直接抛
 *      "ViewTreeLifecycleOwner not found"，这是"Service 里跑 Compose"最常见的翻车点；
 *   ③ **聊天用的是独立 ViewModel 实例**：Service 活的比 Activity 久，
 *      不能引用界面里的那个（Activity 销毁后它就被清了）。消息本身来自 Room，
 *      所以浮窗与 App 里看到的是**同一份记录**，只是各自的流式状态独立；
 *   ④ **收起时 FLAG_NOT_FOCUSABLE、展开时去掉**：不收着的话输入法一弹就顶到气泡上；
 *      展开时不去掉则输入框点不动、打字没反应；
 *   ⑤ **位置夹取 + 吸附边缘**（[OverlayGeometry]）：气泡拖出屏幕就再也点不到了。
 */
class OverlayChatService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var store: ViewModelStore
    private lateinit var savedStateController: SavedStateRegistryController

    private lateinit var wm: WindowManager
    private var params: WindowManager.LayoutParams? = null
    private var overlayView: ComposeView? = null
    private var chatVm: ChatViewModel? = null

    /** 展开/收起：Compose 侧直接读这个状态 */
    private val expanded = mutableStateOf(false)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        store = ViewModelStore()
        savedStateController = SavedStateRegistryController.create(this).apply { performRestore(null) }
        wm = getSystemService(WindowManager::class.java)
        // 独立实例：Service 比 Activity 活得久，不能引用界面那个（会被清掉）
        chatVm = ViewModelProvider(this, ChatViewModel.Factory)[ChatViewModel::class.java]
        startForegroundSafely()
        attachOverlay()
        running = true
        _runningFlow.value = true
        NekoLog.info(NekoLog.MODULE_UI, "overlay_started", "悬浮窗聊天已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                NekoLog.info(NekoLog.MODULE_UI, "overlay_hidden", "用户关闭悬浮窗")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> expanded.value = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        _runningFlow.value = false
        runCatching { overlayView?.let { wm.removeView(it) } }
        overlayView = null
        params = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        NekoLog.info(NekoLog.MODULE_UI, "overlay_stopped", "悬浮窗聊天已停止")
        super.onDestroy()
    }

    // ---------------- 窗口 ----------------

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮窗聊天", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val hideIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayChatService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cat)
            .setContentTitle("猫娘助手悬浮窗")
            .setContentText("点气泡可以随时聊天")
            .setOngoing(true)
            .addAction(0, "关闭悬浮窗", hideIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        val dm = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 收起时不吃焦点（否则输入法会顶到气泡上）；展开时由 setExpanded 去掉
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - 72 * dm.density).toInt().coerceAtLeast(0)
            y = (dm.heightPixels * 0.35f).toInt()
        }

        val view = ComposeView(this).apply {
            // 三个 owner 缺一不可：ComposeView 拿 Recomposer 时要它们
            setViewTreeLifecycleOwner(this@OverlayChatService)
            setViewTreeViewModelStoreOwner(this@OverlayChatService)
            setViewTreeSavedStateRegistryOwner(this@OverlayChatService)
            setContent {
                val vm = chatVm
                // 组合期不写状态（会反复触发重组）：拿不到 VM 就什么都不画
                if (vm != null) {
                    val chat by vm.state.collectAsState()
                    val appearance by remember { ThemeStore(NekoApp.context()).settings }
                        .collectAsState(initial = AppearanceSettings())
                    NekoTheme(
                        themeId = appearance.themeId,
                        fontScale = appearance.fontScale,
                        motionLevel = appearance.motionLevel
                    ) {
                        if (expanded.value) {
                            OverlayPanel(
                                chat = chat,
                                onSend = { vm.send(it) },
                                onStop = { vm.stop() },
                                onCollapse = { setExpanded(false) },
                                onClose = { stopSelf() },
                                onAnswerConfirm = { vm.answerConfirm(it) },
                                onDrag = { dx, dy -> moveBy(dx, dy) },
                                onDragEnd = { snapToEdge() }
                            )
                        } else {
                            OverlayBubble(
                                streaming = chat.streaming,
                                onClick = { setExpanded(true) },
                                onDrag = { dx, dy -> moveBy(dx, dy) },
                                onDragEnd = { snapToEdge() }
                            )
                        }
                    }
                }
            }
        }
        runCatching {
            wm.addView(view, p)
            overlayView = view
            params = p
        }.onFailure {
            NekoLog.error(NekoLog.MODULE_UI, "overlay_add_failed", it.javaClass.simpleName + ": " + it.message)
            stopSelf()
        }
    }

    private fun setExpanded(e: Boolean) {
        expanded.value = e
        val v = overlayView ?: return
        val p = params ?: return
        p.flags = if (e) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        p.softInputMode = if (e) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        runCatching { wm.updateViewLayout(v, p) }
        // 面板比气泡大得多：等布局完再夹一次，否则展开后可能半个面板在屏幕外
        v.post { reclamp() }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = overlayView ?: return
        val p = params ?: return
        val dm = resources.displayMetrics
        val (nx, ny) = OverlayGeometry.clamp(
            (p.x + dx).toInt(), (p.y + dy).toInt(),
            v.width, v.height, dm.widthPixels, dm.heightPixels
        )
        p.x = nx; p.y = ny
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun snapToEdge() {
        val v = overlayView ?: return
        val p = params ?: return
        p.x = OverlayGeometry.snapToEdge(p.x, v.width, resources.displayMetrics.widthPixels)
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun reclamp() {
        val v = overlayView ?: return
        val p = params ?: return
        val dm = resources.displayMetrics
        val (nx, ny) = OverlayGeometry.clamp(p.x, p.y, v.width, v.height, dm.widthPixels, dm.heightPixels)
        if (nx != p.x || ny != p.y) {
            p.x = nx; p.y = ny
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.nekonyan.assistant.overlay.SHOW"
        const val ACTION_HIDE = "com.nekonyan.assistant.overlay.HIDE"
        const val ACTION_EXPAND = "com.nekonyan.assistant.overlay.EXPAND"

        private const val CHANNEL_ID = "nekonyan_overlay"
        private const val NOTIF_ID = 1002

        @Volatile var running: Boolean = false
            private set

        /**
         * 给界面观察的运行状态。
         * [running] 是给代码判断用的普通标志，Compose 读它不会重组 —— 所以另给一条流，
         * 设置页与侧边栏的开关才能跟着实际状态走（而不是点了不变）。
         */
        private val _runningFlow = MutableStateFlow(false)
        val runningFlow: StateFlow<Boolean> = _runningFlow.asStateFlow()

        /** 是否已授权「显示在其他应用上层」 */
        fun canDraw(context: Context): Boolean = android.provider.Settings.canDrawOverlays(context)

        fun show(context: Context) {
            val i = Intent(context, OverlayChatService::class.java).setAction(ACTION_SHOW)
            ContextCompat.startForegroundService(context, i)
        }

        fun hide(context: Context) {
            runCatching {
                context.startService(Intent(context, OverlayChatService::class.java).setAction(ACTION_HIDE))
            }
        }
    }
}
