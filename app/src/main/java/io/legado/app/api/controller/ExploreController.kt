package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object ExploreController {
    val sources: ReturnData
        get() = ReturnData().setData(appDb.bookSourceDao.getWebExploreSources()
            .map { mapOf("url" to it.bookSourceUrl, "name" to it.bookSourceName,
                "group" to it.bookSourceGroup) })

    fun explore(parameters: Map<String, List<String>>, categories: Boolean): ReturnData {
        val sourceUrl = parameters["sourceUrl"]?.firstOrNull()
            ?: return ReturnData().setErrorMsg("请选择书源")
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: return ReturnData().setErrorMsg("书源不存在")
        if (!source.enabled || !source.enabledExplore) {
            return ReturnData().setErrorMsg("此书源未启用发现")
        }
        return try {
            runBlocking {
                withTimeout(60000L) {
                    val kinds = source.exploreKinds()
                    val parseError = kinds.firstOrNull { it.title.startsWith("ERROR:") }
                    if (parseError != null) {
                        return@withTimeout ReturnData().setErrorMsg(parseError.title)
                    }
                    if (categories) {
                        ReturnData().setData(kinds)
                    } else {
                        val url = parameters["url"]?.firstOrNull()
                        val page = parameters["page"]?.firstOrNull()?.toIntOrNull()
                        if (url.isNullOrBlank() || kinds.none { it.url == url }
                            || page == null || page < 1) {
                            return@withTimeout ReturnData().setErrorMsg("发现分类或页码无效")
                        }
                        ReturnData().setData(WebBook.exploreBookAwait(source, url, page)
                            .map { it.toBook() })
                    }
                }
            }
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "书源发现加载失败")
        }
    }
}
