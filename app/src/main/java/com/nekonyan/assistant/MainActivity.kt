package com.nekonyan.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nekonyan.assistant.ui.NekoAppRoot

/**
 * 唯一的 Activity（需求：应用名「猫娘助手」，可安装启动；默认橘猫色）。
 *
 * 说明：主界面刻意不做多 Activity —— 侧边菜单是唯一导航入口，
 * 各功能页由 Compose 内的路由切换，避免返回栈与悬浮窗服务互相干扰。
 *
 * 系统栏策略（按用户反馈调整）：
 *   · **隐藏底部导航键**：三键导航会直接压在聊天输入栏和紧急停止按钮上，
 *     而这两个控件是本 App 最不能点不到的东西 —— 所以干脆隐藏，
 *     需要时从屏幕边缘上滑即可临时唤出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）；
 *   · **保留状态栏**：时间/电量/网络对用户有用，隐藏它只会让人困惑；
 *   · 布局侧仍按 safeDrawing 留白（见 MainScreen），因此即使导航键被临时唤出、
 *     或用户改回三键导航，输入栏也不会被盖住 —— 不依赖"藏起来"这一个手段。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 需求：加载动画为小猫走路/尾巴摆动 —— 这里用系统开屏 + 猫图标
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBars()

        setContent {
            NekoAppRoot()
        }
    }

    /** 系统在某些时机（如弹窗、任务切换）会把导航键放回来，重新获得焦点时再收起 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBars()
    }

    private fun hideNavigationBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
