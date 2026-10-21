package io.legado.app.ui.book.agent

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.utils.GSON
import io.legado.app.utils.putPrefStringSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** 使用本机合成模型与站点，实际执行加载、规则解析、校验及数据库保存，不访问 AI 供应商。 */
@RunWith(AndroidJUnit4::class)
class BookSourceAgentIntegrationTest {
    @Test
    fun createAndEditUsePrivateRequestsAndPreserveOriginalSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousDisabled = AgentToolPreferences.disabled(context)
        val server = FixtureServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val source = server.source()
        val agent = AgentViewModel(context.applicationContext as Application, restoreLatestConversation = false)
        AgentViewModel::class.java.getDeclaredField("currentSupplier").apply {
            isAccessible = true
            set(agent, AiSource(model = "synthetic-source-agent", baseUrl = server.baseUrl))
        }
        try {
            context.putPrefStringSet(PreferKey.aiDisabledTools, mutableSetOf())
            val created = agent.createBookSource(server.baseUrl, "创建合成测试书源")
            assertTrue(created, created.startsWith("书源创建成功"))
            val original = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!
            assertEquals("AI生成", original.bookSourceGroup)
            original.bookSourceGroup = "合成测试分组"
            original.enabled = false
            original.enabledExplore = false
            original.customOrder = 4321
            appDb.bookSourceDao.insert(original)

            val edited = agent.editBookSource(source.bookSourceUrl, "修复规则并保留原配置")
            assertTrue(edited, edited.startsWith("书源编辑成功"))
            val saved = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!
            assertEquals("合成测试分组", saved.bookSourceGroup)
            assertFalse(saved.enabled)
            assertFalse(saved.enabledExplore)
            assertEquals(4321, saved.customOrder)
            assertEquals(4, server.requests.size)
            server.requests.forEach { request ->
                val names = request.getAsJsonArray("tools").map {
                    it.asJsonObject.getAsJsonObject("function").get("name").asString
                }.toSet()
                assertEquals(BookSourceAgent.tools, names)
                assertTrue(request.getAsJsonArray("messages")[0].asJsonObject
                    .get("content").asString.contains("书源开发子代理"))
            }
            val editInitial = server.requests[2].getAsJsonArray("messages")[1].asJsonObject
                .get("content").asString
            assertTrue(editInitial.contains("合成测试分组"))
            assertTrue(editInitial.contains("修复规则并保留原配置"))
            val parentHistory = AgentViewModel::class.java.getDeclaredField("history").apply { isAccessible = true }
                .get(agent) as List<*>
            assertTrue("子代理页面和调试历史不得写入主会话", parentHistory.isEmpty())

            val build = AgentViewModel::class.java.getDeclaredMethod(
                "buildChatRequest", AiSource::class.java, List::class.java
            ).apply { isAccessible = true }
            val main = build.invoke(agent, AiSource(model = "test"), emptyList<AgentChatTurn>()) as JsonObject
            val mainNames = main.getAsJsonArray("tools").map {
                it.asJsonObject.getAsJsonObject("function").get("name").asString
            }.toSet()
            assertTrue(mainNames.containsAll(setOf("create_book_source", "edit_book_source")))
            assertTrue(mainNames.intersect(BookSourceAgent.privateTools).isEmpty())

            val missing = agent.editBookSource(server.baseUrl + "/missing", "修复")
            assertTrue(missing.contains("未找到书源"))
            assertEquals(4, server.requests.size)
            context.putPrefStringSet(PreferKey.aiDisabledTools, mutableSetOf("save_book_source"))
            val disabled = agent.editBookSource(source.bookSourceUrl, "修复")
            assertTrue(disabled.contains("工具已禁用"))
            assertEquals(4, server.requests.size)
        } finally {
            context.putPrefStringSet(PreferKey.aiDisabledTools, previousDisabled.toMutableSet())
            appDb.bookSourceDao.delete(source.bookSourceUrl)
            server.stop()
        }
    }

    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val baseUrl: String get() = "http://127.0.0.1:$listeningPort"

        fun source() = BookSource(
            bookSourceUrl = "$baseUrl/", bookSourceName = "子代理合成书源",
            searchUrl = "$baseUrl/search?key={{key}}",
            ruleSearch = SearchRule(
                bookList = "class.item", name = "tag.a@text", bookUrl = "tag.a@href",
                author = "class.author@text"
            ),
            ruleBookInfo = BookInfoRule(name = "tag.h1@text", tocUrl = "class.toc@href"),
            ruleToc = TocRule(chapterList = "class.chapters@tag.a", chapterName = "text", chapterUrl = "href"),
            ruleContent = ContentRule(content = "id.content@text")
        )

        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/chat/completions") {
                val files = hashMapOf<String, String>()
                session.parseBody(files)
                val request = JsonParser.parseString(files.getValue("postData")).asJsonObject
                requests.add(request)
                val update = requests.size % 2 == 1
                val call = JsonObject().apply {
                    addProperty("index", 0)
                    addProperty("id", "fixture-${requests.size}")
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", if (update) "update_book_source" else "save_book_source")
                        addProperty("arguments", if (update) JsonObject().apply {
                            addProperty("sourceJson", GSON.toJson(source()))
                        }.toString() else "{}")
                    })
                }
                val chunk = JsonObject().apply {
                    add("choices", JsonArray().apply {
                        add(JsonObject().apply {
                            add("delta", JsonObject().apply {
                                add("tool_calls", JsonArray().apply { add(call) })
                            })
                        })
                    })
                }
                return newFixedLengthResponse(Response.Status.OK, "text/event-stream", "data: $chunk\n\ndata: [DONE]\n\n")
            }
            val html = when (session.uri) {
                "/search" -> "<div class='item'><a href='$baseUrl/book'>测试书</a><span class='author'>测试作者</span></div>"
                "/book" -> "<h1>测试书</h1><a class='toc' href='$baseUrl/toc'>目录</a>"
                "/toc" -> "<div class='chapters'><a href='$baseUrl/chapter'>第一章</a></div>"
                "/chapter" -> "<div id='content'>这是一段用于验证书源子代理的合成章节正文。</div>"
                else -> "<h1>合成测试小说站</h1>"
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }
}
