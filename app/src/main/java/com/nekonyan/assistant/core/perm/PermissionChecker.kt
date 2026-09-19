package com.nekonyan.assistant.core.perm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限检测与跳转（Android 侧）。
 *
 * 三条原则：
 *   · **一切检测都包在 runCatching 里**：权限检测本身抛异常会让引导页打不开，
 *     而"引导页崩溃"比"没授权"严重得多；
 *   · 检测不到的（厂商自启动 / 后台弹出）一律返回 [PermissionState.MANUAL]，
 *     **不假装已授权**，也不假装能申请；
 *   · 屏幕录制系统不允许预授权 → 返回 MANUAL，文案里说明会在使用时弹窗。
 */
object PermissionChecker {

    fun allStates(context: Context): Map<String, PermissionState> =
        PermissionGuide.ITEMS.associate { it.key to state(context, it.key) }

    fun state(context: Context, key: String): PermissionState = runCatching {
        when (key) {
            PermissionGuide.KEY_NOTIFICATION ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) PermissionState.NOT_APPLICABLE
                else runtime(context, Manifest.permission.POST_NOTIFICATIONS)

            PermissionGuide.KEY_OVERLAY ->
                if (Settings.canDrawOverlays(context)) PermissionState.GRANTED else PermissionState.DENIED

            PermissionGuide.KEY_ACCESSIBILITY -> {
                // 本应用已声明 NekoAccessibilityService：系统里能查到就是已开启，
                // 查不到就是"可申请但未开启"（DENIED），引导页据此显示「去授权」并跳无障碍设置。
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val connected = com.nekonyan.assistant.core.access.NekoAccessibilityService.isConnected()
                if (connected || enabled.contains(context.packageName)) PermissionState.GRANTED
                else PermissionState.DENIED
            }

            PermissionGuide.KEY_SCREEN_CAPTURE -> PermissionState.MANUAL

            PermissionGuide.KEY_MEDIA ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val audio = runtime(context, Manifest.permission.READ_MEDIA_AUDIO)
                    val images = runtime(context, Manifest.permission.READ_MEDIA_IMAGES)
                    if (audio == PermissionState.GRANTED || images == PermissionState.GRANTED) {
                        PermissionState.GRANTED
                    } else PermissionState.DENIED
                } else {
                    runtime(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                }

            PermissionGuide.KEY_RECORD_AUDIO -> runtime(context, Manifest.permission.RECORD_AUDIO)

            PermissionGuide.KEY_BATTERY -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                    PermissionState.GRANTED
                } else PermissionState.DENIED
            }

            PermissionGuide.KEY_BACKGROUND_POPUP,
            PermissionGuide.KEY_AUTOSTART -> PermissionState.MANUAL

            else -> PermissionState.NOT_APPLICABLE
        }
    }.getOrDefault(PermissionState.NOT_APPLICABLE)

    private fun runtime(context: Context, permission: String): PermissionState =
        runCatching {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                PermissionState.GRANTED
            } else PermissionState.DENIED
        }.getOrDefault(PermissionState.DENIED)

    /**
     * 需求：提供跳转按钮。
     * 返回 null 表示该项没有可跳转的系统页面（例如屏幕录制只能在真正使用时弹窗授权）。
     */
    fun jumpIntent(context: Context, key: String): Intent? = runCatching {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        when (key) {
            PermissionGuide.KEY_NOTIFICATION ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

            PermissionGuide.KEY_OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)

            PermissionGuide.KEY_ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            PermissionGuide.KEY_BATTERY ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)

            PermissionGuide.KEY_BACKGROUND_POPUP,
            PermissionGuide.KEY_AUTOSTART,
            PermissionGuide.KEY_MEDIA,
            PermissionGuide.KEY_RECORD_AUDIO ->
                // 厂商差异太大：统一跳到"应用详情"，用户在里面找自启动/后台弹出/权限
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)

            else -> null
        }
    }.getOrNull()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 需要运行时申请（走系统弹窗）的权限；其余靠跳设置页 */
    fun runtimePermissions(key: String): List<String> = when (key) {
        PermissionGuide.KEY_NOTIFICATION ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        PermissionGuide.KEY_MEDIA ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
            } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        PermissionGuide.KEY_RECORD_AUDIO -> listOf(Manifest.permission.RECORD_AUDIO)
        else -> emptyList()
    }
}
