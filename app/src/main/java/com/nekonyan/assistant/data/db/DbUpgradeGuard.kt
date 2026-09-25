package com.nekonyan.assistant.data.db

import android.content.Context
import com.nekonyan.assistant.core.log.NekoLog
import java.io.File

/**
 * 升级前的数据库备份（配合 [DbUpgradePolicy]）。
 *
 * 调用时机**必须在 Room 打开库之前**（[NekoDatabase.build] 里、`Room.databaseBuilder` 之前）：
 * 一旦 Room 完成重建，旧数据就没了，再备份也来不及。
 *
 * 只做文件拷贝，不解析、不修改数据库内容 —— 这样即使备份逻辑有 bug，也不会损坏原库。
 */
object DbUpgradeGuard {

    private const val DIR = "db_backup"
    private const val MAIN = "nekonyan.db"

    @Volatile
    var lastBackupPath: String? = null
        private set

    /**
     * 如果磁盘上的库版本低于 [currentVersion]，把整份库（含 WAL/SHM）拷到私有目录。
     *
     * @return 备份目录（没备份则为 null）
     */
    fun backupIfOutdated(context: Context, currentVersion: Int): String? {
        val dbFile = context.getDatabasePath(MAIN)
        if (!dbFile.isFile) return null

        val version = runCatching {
            dbFile.inputStream().use { ins ->
                val head = ByteArray(DbUpgradePolicy.USER_VERSION_OFFSET + 4)
                var read = 0
                while (read < head.size) {
                    val n = ins.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                DbUpgradePolicy.readUserVersion(head.copyOf(read))
            }
        }.getOrNull()

        if (!DbUpgradePolicy.needsBackup(version, currentVersion)) return null

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = DbUpgradePolicy.backupFileName(version!!, System.currentTimeMillis())
        val target = File(dir, name)
        return runCatching {
            dbFile.copyTo(target, overwrite = true)
            // WAL 里有尚未合并进主库的最新数据，一起备份才算完整
            File(dbFile.path + "-wal").takeIf { it.isFile }?.copyTo(File(dir, "$name-wal"), overwrite = true)
            File(dbFile.path + "-shm").takeIf { it.isFile }?.copyTo(File(dir, "$name-shm"), overwrite = true)
            prune(dir)
            lastBackupPath = target.absolutePath
            NekoLog.warn(
                NekoLog.MODULE_STORE, "db_backup_before_upgrade",
                "库版本 v$version → v$currentVersion：升级会重建库，已先把旧库备份到 ${target.name}"
            )
            dir.absolutePath
        }.getOrElse {
            NekoLog.error(NekoLog.MODULE_STORE, "db_backup_failed", it.javaClass.simpleName + ": " + it.message)
            null
        }
    }

    /** 只留最近 [DbUpgradePolicy.KEEP_BACKUPS] 份主库（连同它的 -wal/-shm） */
    private fun prune(dir: File) {
        val mains = dir.listFiles { f -> f.isFile && f.name.startsWith("nekonyan_v") && f.name.endsWith(".db") }
            ?.map { it.name }?.sorted() ?: return
        DbUpgradePolicy.toPrune(mains).forEach { old ->
            File(dir, old).delete()
            File(dir, "$old-wal").delete()
            File(dir, "$old-shm").delete()
        }
    }
}
