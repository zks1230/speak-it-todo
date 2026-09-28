package com.saym.phone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动自检：在 JVM 上**真实创建**每个 Activity 与本地数据库。
 *
 * 为什么需要这个：
 * 开发这台机器是阿里云 ECS 虚拟机，没有嵌套虚拟化，Android 模拟器跑不起来，
 * 所以无法在设备上验证。Robolectric 能在纯 JVM 上真实加载 Activity / Room / 资源，
 * 把"一进页面就崩"这类问题在编译期之外也暴露出来。
 *
 * 注意：这里不做 UI 断言（那需要真机），只验证**能不能创建起来、数据层通不通**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "zh-rCN")
class LaunchSmokeTest {

    @Before
    fun setUp() {
        // 确保 Application.onCreate 跑过（App.instance 被赋值）
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull("Application 上下文为空", app)
        assertTrue("App.instance 未初始化 —— AndroidManifest 缺少 android:name=\".App\"?", App.isReady())
    }

    @Test
    fun `本地数据库可读写`() {
        val dao = com.saym.phone.db.SaymDb.get().todoDao()
        val now = java.time.Instant.now().toString()
        dao.upsert(
            com.saym.phone.db.TodoLocal(
                id = "t1", title = "测试待办", deadline = null, subject = null, notes = null,
                sourceInputType = "TEXT", sourceInputRaw = null, isCompleted = false, completedAt = null,
                isKeyTask = false, remindAt = null, remindOffsetMinutes = 0,
                createdAt = now, updatedAt = now, originDevice = "phone", deleted = false, deletedAt = null
            )
        )
        val all = dao.all()
        assertTrue("写入后读不到数据", all.any { it.id == "t1" })
    }

    @Test
    fun `首页能创建并渲染`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = c.get()
        assertNotNull("MainActivity 创建失败", activity)
        assertNotNull("根布局为空（可能 render 抛异常）", activity.window.decorView)
        // 触发一次 onResume 路径（数据读取 + 渲染 + 手表推送尝试）
        c.pause().resume()
        c.destroy()
    }

    @Test
    fun `设置页能创建`() {
        val c = Robolectric.buildActivity(ConfigActivity::class.java).setup()
        assertNotNull("ConfigActivity 创建失败", c.get())
        c.destroy()
    }

    @Test
    fun `输入页能创建`() {
        val c = Robolectric.buildActivity(InputActivity::class.java).setup()
        assertNotNull("InputActivity 创建失败", c.get())
        c.destroy()
    }

    @Test
    fun `输入暂存页能创建`() {
        val c = Robolectric.buildActivity(InputHistoryActivity::class.java).setup()
        assertNotNull("InputHistoryActivity 创建失败", c.get())
        c.destroy()
    }

    @Test
    fun `待办详情页能创建`() {
        val intent = android.content.Intent(
            ApplicationProvider.getApplicationContext(), TodoDetailActivity::class.java
        ).putExtra("id", "t1")
        val c = Robolectric.buildActivity(TodoDetailActivity::class.java, intent).setup()
        assertNotNull("TodoDetailActivity 创建失败", c.get())
        c.destroy()
    }

    @Test
    fun `配置存储可保存并读回`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        val saved = cfg.copy(
            models = cfg.models + ("DEEPSEEK" to cfg.models.getValue("DEEPSEEK").copy(apiKey = "sk-test-1234567890"))
        )
        com.saym.phone.cfg.LocalConfigStore.save(ctx, saved)
        val back = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        assertTrue(
            "API Key 保存后读不回来（这就是'API 无法保存'）—— storage=${com.saym.phone.cfg.LocalConfigStore.encryptionMode} err=${com.saym.phone.cfg.LocalConfigStore.lastError}",
            back.models["DEEPSEEK"]?.apiKey == "sk-test-1234567890"
        )
    }
}
