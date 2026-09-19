package com.nekonyan.assistant.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.env.ThermalMonitor
import com.nekonyan.assistant.data.repo.AppearanceSettings
import com.nekonyan.assistant.data.repo.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 外观状态（主题 / 字体 / 动态效果）。
 *
 * 需求相关的三个"自动降级"来源都在这里汇合：
 *   ① 用户手动选择（设置页）
 *   ② 系统「移除动画」（无障碍设置）
 *   ③ 低功耗/发热（[ThermalMonitor]，需求：低功耗、发热、低电量自动降级）
 * 业务界面只消费最终的 [effectiveMotion]，不必各自判断。
 */
class ThemeViewModel(private val store: ThemeStore, private val context: Context) : ViewModel() {

    private val _effectiveMotion = MutableStateFlow(MotionLevel.FULL)

    val settings: StateFlow<AppearanceSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceSettings())

    /** 实际生效的动态效果档位 */
    val effectiveMotion: StateFlow<MotionLevel> = _effectiveMotion

    init {
        // 把「用户设置 + 系统设置 + 热/电状态」合成为最终档位
        viewModelScope.launch {
            combine(
                store.settings,
                ThermalMonitor.state
            ) { s, thermal ->
                val animatorOff = animatorDurationScaleZero()
                if (!s.autoDegrade) {
                    // 用户明确关闭自动降级：只尊重用户选择与系统无障碍设置
                    MotionPolicy.suggest(s.motionLevel, animatorOff, null, 100)
                } else {
                    MotionPolicy.suggest(
                        userLevel = s.motionLevel,
                        animatorScaleZero = animatorOff,
                        thermalCelsius = thermal.celsius,
                        batteryPercent = thermal.batteryPercent
                    )
                }
            }.collect { _effectiveMotion.value = it }
        }
    }

    /** 需求：支持「减少动态效果」——读系统无障碍里的动画缩放 */
    private fun animatorDurationScaleZero(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)

    fun setTheme(id: NekoThemeId) = viewModelScope.launch { store.setTheme(id) }
    fun setFontScale(scale: Float) = viewModelScope.launch { store.setFontScale(scale) }
    fun setMotion(level: MotionLevel) = viewModelScope.launch { store.setMotionLevel(level) }
    fun setAutoDegrade(on: Boolean) = viewModelScope.launch { store.setAutoDegrade(on) }
    fun setAccessibilityMode(on: Boolean) = viewModelScope.launch { store.setAccessibilityMode(on) }
    fun setColorBlindFriendly(on: Boolean) =
        viewModelScope.launch { store.setColorBlindFriendly(on) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = NekoApp.get()
                ThemeViewModel(ThemeStore(app), app)
            }
        }
    }
}

/** 让 CreationExtras 的 import 不被优化掉的占位（AGP 8 的 initializer DSL 需要它） */
private val unusedExtras: CreationExtras? = null
