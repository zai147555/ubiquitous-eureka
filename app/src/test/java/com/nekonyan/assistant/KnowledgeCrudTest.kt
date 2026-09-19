package com.nekonyan.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekonyan.assistant.data.db.KnowledgeCategory
import com.nekonyan.assistant.data.db.KnowledgeItem
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.core.util.NekoMode
import com.nekonyan.assistant.data.repo.KnowledgeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 知识库 CRUD（需求：单元测试覆盖知识库 CRUD；
 * 多个知识库、分类可独立开关、支持文本条目）
 *
 * 用 Robolectric + 内存数据库，测试之间互不影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class KnowledgeCrudTest {

    private lateinit var db: NekoDatabase
    private lateinit var repo: KnowledgeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekoDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = KnowledgeRepository(db.knowledgeDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `新建知识库后可查询到`() = runTest {
        val id = repo.createBase("游戏攻略")
        val bases = repo.observeBases().first()

        assertEquals(1, bases.size)
        assertEquals("游戏攻略", bases.first().name)
        assertEquals(id, bases.first().id)
        assertFalse("默认不折叠", bases.first().collapsed)
    }

    @Test
    fun `新建知识库时空白名称会被兜底`() = runTest {
        repo.createBase("   ")
        assertEquals("未命名知识库", repo.observeBases().first().first().name)
    }

    @Test
    fun `分类挂在知识库下且可独立开关`() = runTest {
        val baseId = repo.createBase("资料")
        val catId = repo.createCategory(baseId, "物资价格")

        val cats = repo.observeCategories(baseId).first()
        assertEquals(1, cats.size)
        assertEquals(baseId, cats.first().knowledgeBaseId)
        assertTrue("新建分类默认对 AI 开启", cats.first().enabledForAI)

        // 需求：分类可独立开关
        repo.setCategoryEnabled(catId, false)
        assertFalse(repo.observeCategories(baseId).first().first().enabledForAI)

        repo.setCategoryEnabled(catId, true)
        assertTrue(repo.observeCategories(baseId).first().first().enabledForAI)
    }

    @Test
    fun `只有开关打开的分类才会被 AI 读取`() = runTest {
        val baseId = repo.createBase("资料")
        val onId = repo.createCategory(baseId, "开启的分类")
        val offId = repo.createCategory(baseId, "关闭的分类")
        repo.setCategoryEnabled(offId, false)

        repo.addTextItem(onId, "这条应该被 AI 读到")
        repo.addTextItem(offId, "这条不应该被 AI 读到")

        val forAI = db.knowledgeDao().itemsForAI()
        assertEquals(1, forAI.size)
        assertEquals("这条应该被 AI 读到", forAI.first().textContent)
    }

    @Test
    fun `文本条目可增可删`() = runTest {
        val baseId = repo.createBase("资料")
        val catId = repo.createCategory(baseId, "分类")

        repo.addTextItem(catId, "条目一")
        repo.addTextItem(catId, "条目二")
        assertEquals(2, repo.countItems(catId))

        val item = repo.observeItems(catId).first().first()
        repo.deleteItem(item)
        assertEquals(1, repo.countItems(catId))
    }

    @Test
    fun `图片条目会记录 imageUri`() = runTest {
        val baseId = repo.createBase("资料")
        val catId = repo.createCategory(baseId, "截图")
        repo.addImageItem(catId, "content://media/external/images/1", "地图截图")

        val item = repo.observeItems(catId).first().first()
        assertEquals(KnowledgeItem.TYPE_IMAGE, item.type)
        assertEquals("content://media/external/images/1", item.imageUri)
    }

    @Test
    fun `删除知识库会级联删除分类与条目`() = runTest {
        val baseId = repo.createBase("资料")
        val catId = repo.createCategory(baseId, "分类")
        repo.addTextItem(catId, "条目")

        val base = repo.observeBases().first().first()
        repo.deleteBase(base)

        assertTrue(repo.observeBases().first().isEmpty())
        assertTrue(repo.observeCategories(baseId).first().isEmpty())
        assertEquals(0, repo.countItems(catId))
    }

    @Test
    fun `折叠状态可切换并持久化`() = runTest {
        val id = repo.createBase("资料")
        repo.setCollapsed(id, true)
        assertTrue(repo.observeBases().first().first().collapsed)
    }

    @Test
    fun `种子数据会建立名为AI且不可管理的AI知识库`() = runTest {
        NekoDatabase.seed(db)

        val aiKb = db.aiKnowledgeDao().base()
        assertNotNull("首启必须存在 AI 知识库", aiKb)
        assertEquals("AI", aiKb!!.name)
        assertFalse("需求：AI 知识库初始不可管理", aiKb.manageable)
        assertTrue(aiKb.readableByAI)
        assertTrue(aiKb.writableByAI)
    }

    @Test
    fun `种子数据为每个模式建立保守默认权限`() = runTest {
        NekoDatabase.seed(db)

        NekoMode.entries.forEach { mode ->
            val p = db.modeTaskDao().permission(mode.key)
            assertNotNull("模式 ${mode.key} 应有权限记录", p)
            assertFalse("默认不允许 AI 执行", p!!.allowAIExecute)
            assertTrue("默认需要确认", p.needConfirm)
            assertFalse("默认不写入 AI 知识库", p.writeToAIKnowledge)
        }
    }

    @Test
    fun `种子数据幂等_重复调用不会重复插入`() = runTest {
        NekoDatabase.seed(db)
        NekoDatabase.seed(db)
        NekoDatabase.seed(db)

        val bases = db.aiKnowledgeDao().observeBase().first()
        assertNotNull(bases)
        assertEquals(1, db.personaDao().observeAll().first().size)
    }

    @Test
    fun `默认人格会被注入系统提示词片段`() = runTest {
        NekoDatabase.seed(db)
        val fragment = db.personaDao().systemPromptFragment(null)
        assertTrue("提示词里应含人格名称", fragment.contains("猫娘"))
        assertTrue("描述也应注入", fragment.contains("人格描述"))
    }
}
