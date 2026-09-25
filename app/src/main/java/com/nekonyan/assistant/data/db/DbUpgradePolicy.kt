package com.nekonyan.assistant.data.db

/**
 * 数据库升级的**判定逻辑**（纯函数，不依赖 Android，便于单元测试）。
 *
 * 背景：本项目用的是 `fallbackToDestructiveMigration()` —— 表结构一变就重建库。
 * 这是有意的取舍（手写迁移 SQL 必须与 Room 生成的建表语句逐字节一致，写错的表现是
 * **升级即崩**，比丢数据更难挽回）。但它的代价是：**用户的聊天记录、知识库、任务会无声消失**。
 *
 * 所以这里不做迁移，只做一件更稳的事：**在 Room 打开库之前，把旧库整份备份出来**。
 * 升级照样重建，但数据还在 `filesDir/db_backup/` 里，可人工恢复、可后续做导入。
 */
object DbUpgradePolicy {

    /** SQLite 文件头魔数（16 字节，"SQLite format 3" + \0） */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** user_version 在 SQLite 文件头里的偏移（4 字节大端） */
    const val USER_VERSION_OFFSET = 60

    /** 最多保留几份备份（再多就删最旧的；备份是救援手段，不是归档方案） */
    const val KEEP_BACKUPS = 3

    /**
     * 从 SQLite 文件头里读 user_version（Room 用它记录数据库版本）。
     *
     * @return 版本号；文件太短或不是 SQLite 文件时返回 null（调用方据此放弃备份，而不是瞎猜）
     */
    fun readUserVersion(header: ByteArray): Int? {
        if (header.size < USER_VERSION_OFFSET + 4) return null
        for (i in SQLITE_MAGIC.indices) {
            if (header[i] != SQLITE_MAGIC[i]) return null
        }
        return ((header[USER_VERSION_OFFSET].toInt() and 0xFF) shl 24) or
            ((header[USER_VERSION_OFFSET + 1].toInt() and 0xFF) shl 16) or
            ((header[USER_VERSION_OFFSET + 2].toInt() and 0xFF) shl 8) or
            (header[USER_VERSION_OFFSET + 3].toInt() and 0xFF)
    }

    /**
     * 要不要备份。
     *
     * 只有"旧库版本 **低于** 当前版本"才备份 —— 那正是 Room 会重建库的时刻。
     * 同版本（正常启动）不备份，避免每次开 App 都拷一遍库。
     */
    fun needsBackup(userVersion: Int?, currentVersion: Int): Boolean =
        userVersion != null && userVersion in 1 until currentVersion

    /** 备份文件名：带上旧版本号与时间，便于人工辨认 */
    fun backupFileName(oldVersion: Int, timestampMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        return "nekonyan_v${oldVersion}_${fmt.format(java.util.Date(timestampMs))}.db"
    }

    /** 需要按"最旧优先"删除的备份（传已按时间升序排好的名字列表），保留最新的 [KEEP_BACKUPS] 份 */
    fun toPrune(sortedAscending: List<String>): List<String> =
        if (sortedAscending.size <= KEEP_BACKUPS) emptyList()
        else sortedAscending.take(sortedAscending.size - KEEP_BACKUPS)
}
