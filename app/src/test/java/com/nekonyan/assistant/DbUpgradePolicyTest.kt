package com.nekonyan.assistant

import com.nekonyan.assistant.data.db.DbUpgradePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库升级备份判定的单元测试。
 *
 * 为什么值得测：这段逻辑的失效方式是"**用户数据无声消失**"——
 * 版本判断错一格，要么该备份时不备份（丢数据），要么每次都备份（无谓地拷库）。
 * 而它跑在 App 启动路径上，真机上很难复现。
 */
class DbUpgradePolicyTest {

    /** 造一个合法的 SQLite 文件头，指定 user_version */
    private fun sqliteHeader(version: Int, size: Int = 100): ByteArray {
        val b = ByteArray(size)
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        // 故意允许造"截断文件"（size 很小时不能越界写，否则测的是辅助函数的 bug）
        if (b.size >= magic.size) magic.copyInto(b, 0)
        if (b.size >= DbUpgradePolicy.USER_VERSION_OFFSET + 4) {
            b[DbUpgradePolicy.USER_VERSION_OFFSET] = ((version ushr 24) and 0xFF).toByte()
            b[DbUpgradePolicy.USER_VERSION_OFFSET + 1] = ((version ushr 16) and 0xFF).toByte()
            b[DbUpgradePolicy.USER_VERSION_OFFSET + 2] = ((version ushr 8) and 0xFF).toByte()
            b[DbUpgradePolicy.USER_VERSION_OFFSET + 3] = (version and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun reads_user_version_big_endian() {
        // 大端！写成小端会读成 50331648 这种数，然后"永远觉得需要备份"
        assertEquals(3, DbUpgradePolicy.readUserVersion(sqliteHeader(3)))
        assertEquals(1, DbUpgradePolicy.readUserVersion(sqliteHeader(1)))
        assertEquals(258, DbUpgradePolicy.readUserVersion(sqliteHeader(258)))
        assertEquals(0, DbUpgradePolicy.readUserVersion(sqliteHeader(0)))
    }

    @Test
    fun rejects_non_sqlite_or_truncated_files() {
        // 不是 SQLite：交给调用方放弃备份，而不是瞎猜一个版本号
        assertNull(DbUpgradePolicy.readUserVersion(ByteArray(100)))
        assertNull(DbUpgradePolicy.readUserVersion("PK\u0003\u0004".toByteArray()))
        // 太短（连 user_version 都放不下）
        assertNull(DbUpgradePolicy.readUserVersion(sqliteHeader(3, size = 40)))
        assertNull(DbUpgradePolicy.readUserVersion(ByteArray(0)))
    }

    @Test
    fun backs_up_only_when_disk_version_is_older() {
        assertTrue("v1 库升到 v3 要备份", DbUpgradePolicy.needsBackup(1, 3))
        assertTrue("v2 库升到 v3 要备份", DbUpgradePolicy.needsBackup(2, 3))
        assertFalse("同版本是正常启动，不该拷库", DbUpgradePolicy.needsBackup(3, 3))
        assertFalse("读到 0 = 空库/新库", DbUpgradePolicy.needsBackup(0, 3))
        assertFalse("读不出来就别动", DbUpgradePolicy.needsBackup(null, 3))
        // 降级（装了旧版）不备份：那是另一回事，备份也救不了
        assertFalse(DbUpgradePolicy.needsBackup(5, 3))
    }

    @Test
    fun backup_name_carries_version_and_is_stable_per_timestamp() {
        val n = DbUpgradePolicy.backupFileName(2, 1_700_000_000_000L)
        assertTrue("要能看出是从哪个版本备的：$n", n.startsWith("nekonyan_v2_"))
        assertTrue("要是 .db 结尾：$n", n.endsWith(".db"))
        assertEquals(n, DbUpgradePolicy.backupFileName(2, 1_700_000_000_000L))
        assertFalse(n == DbUpgradePolicy.backupFileName(3, 1_700_000_000_000L))
    }

    @Test
    fun keeps_only_the_newest_backups() {
        // 已按时间升序
        val many = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b"), DbUpgradePolicy.toPrune(many))
        // 不超过上限时什么都不删
        assertTrue(DbUpgradePolicy.toPrune(listOf("a", "b")).isEmpty())
        assertTrue(DbUpgradePolicy.toPrune(emptyList()).isEmpty())
        assertEquals(DbUpgradePolicy.KEEP_BACKUPS, 3)
    }
}
