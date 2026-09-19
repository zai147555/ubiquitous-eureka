package com.nekonyan.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.db.NekoMode
import com.nekonyan.assistant.data.db.Task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 任务 CRUD（需求：单元测试覆盖任务 CRUD；
 * 任务必须含「名称 + 消息」，按模式分独立列表，记录最后执行时间与状态）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TaskCrudTest {

    private lateinit var db: NekoDatabase
    private val dao get() = db.taskDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekoDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun task(
        id: String,
        name: String = "日常任务",
        mode: String = NekoMode.CHAT_ONLY.key,
        message: String = "帮我看看现在屏幕上是什么"
    ) = Task(id = id, name = name, mode = mode, message = message)

    @Test
    fun `新建任务后可查询`() = runTest {
        dao.upsert(task("t1"))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("日常任务", all.first().name)
        assertEquals("帮我看看现在屏幕上是什么", all.first().message)
        assertTrue(all.first().enabled)
        assertNull("尚未执行过", all.first().lastRunAt)
    }

    @Test
    fun `按模式分独立列表`() = runTest {
        dao.upsert(task("t1", mode = NekoMode.CHAT_ONLY.key))
        dao.upsert(task("t2", mode = NekoMode.AUTO_GAME.key))
        dao.upsert(task("t3", mode = NekoMode.AUTO_GAME.key))

        assertEquals(1, dao.observeByMode(NekoMode.CHAT_ONLY.key).first().size)
        assertEquals(2, dao.observeByMode(NekoMode.AUTO_GAME.key).first().size)
        assertEquals(0, dao.observeByMode(NekoMode.COMPANION.key).first().size)
    }

    @Test
    fun `启用状态可切换`() = runTest {
        dao.upsert(task("t1"))
        dao.setEnabled("t1", false)
        assertFalse(dao.byId("t1")!!.enabled)
    }

    @Test
    fun `记录最后执行时间与状态`() = runTest {
        dao.upsert(task("t1"))
        val at = 1_789_300_000_000L
        dao.markRun("t1", at, "完成")

        val t = dao.byId("t1")!!
        assertEquals(at, t.lastRunAt)
        assertEquals("完成", t.lastStatus)
    }

    @Test
    fun `修改任务内容`() = runTest {
        dao.upsert(task("t1"))
        val t = dao.byId("t1")!!
        dao.update(t.copy(name = "改过的名字", message = "改过的消息"))

        val updated = dao.byId("t1")!!
        assertEquals("改过的名字", updated.name)
        assertEquals("改过的消息", updated.message)
    }

    @Test
    fun `删除任务`() = runTest {
        val t = task("t1")
        dao.upsert(t)
        assertEquals(1, dao.count())

        dao.delete(t)
        assertEquals(0, dao.count())
        assertNull(dao.byId("t1"))
    }

    @Test
    fun `upsert 同 id 不会产生重复行`() = runTest {
        dao.upsert(task("t1", name = "第一版"))
        dao.upsert(task("t1", name = "第二版"))

        assertEquals(1, dao.count())
        assertEquals("第二版", dao.byId("t1")!!.name)
    }

    @Test
    fun `AI 执行权限默认被拒绝`() = runTest {
        NekoDatabase.seed(db)
        assertFalse(
            "未授予权限时 AI 不得执行任务",
            dao.let { db.modeTaskDao().canAIExecute(NekoMode.AUTO_GAME.key) }
        )
    }

    @Test
    fun `授予权限后 AI 才可执行`() = runTest {
        NekoDatabase.seed(db)
        val mode = NekoMode.AUTO_GAME.key
        val p = db.modeTaskDao().permission(mode)!!
        db.modeTaskDao().upsertPermission(p.copy(allowAIExecute = true))

        assertTrue(db.modeTaskDao().canAIExecute(mode))
        assertNotNull(db.modeTaskDao().observePermission(mode).first())
    }
}
