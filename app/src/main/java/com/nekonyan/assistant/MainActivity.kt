package com.nekonyan.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nekonyan.assistant.ui.NekoAppRoot

/**
 * 唯一的 Activity（需求：应用名「猫娘助手」，可安装启动；默认橘猫色）。
 *
 * 说明：主界面刻意不做多 Activity —— 侧边菜单是唯一导航入口，
 * 各功能页由 Compose 内的路由切换，避免返回栈与悬浮窗服务互相干扰。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 需求：加载动画为小猫走路/尾巴摆动 —— 这里用系统开屏 + 猫图标
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NekoAppRoot()
        }
    }
}
