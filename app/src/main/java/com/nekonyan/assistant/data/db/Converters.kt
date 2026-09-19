package com.nekonyan.assistant.data.db

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room 类型转换：List<String> ↔ JSON 文本。
 *
 * 用途：ModeTaskField.options、InventoryRule.keepList、GamePath.waypoints 等
 * 「一组字符串」的字段。用 JSON 而不是逗号分隔，避免条目里本身含逗号时被切坏。
 */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        val arr = JSONArray()
        value?.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(value)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
