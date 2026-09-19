package com.nekonyan.assistant.core.perm

import android.content.Context

/** 需求：记录「已引导」标志；允许稍后从设置里重新进入引导 */
class PermissionGuideStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isGuided(): Boolean = prefs.getBoolean(KEY_GUIDED, false)

    fun markGuided() {
        prefs.edit().putBoolean(KEY_GUIDED, true).apply()
    }

    /** 设置页里"重新引导"用 */
    fun reset() {
        prefs.edit().putBoolean(KEY_GUIDED, false).apply()
    }

    private companion object {
        const val PREF = "nekonyan_perm_guide"
        const val KEY_GUIDED = "guided"
    }
}
