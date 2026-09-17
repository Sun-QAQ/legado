package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.utils.GSON

/**
 * Agent 工具注册表：所有可调用工具的声明、schema 和执行入口
 */
object AgentTools {

    private const val DEFAULT_LIMIT = 5

    private val searchBooksTool = AgentTool(
        name = "search_books",
        description = "根据书名搜索书籍，可通过 group 指定书源分组或书源名称，可通过 limit 指定展示数量（默认5，最大20），返回搜索结果列表",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "query",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书名关键字")
                    }
                )
                add(
                    "group",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，书源分组名称或具体书源名称，省略时搜索全部已启用书源")
                    }
                )
                add(
                    "limit",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "可选，展示结果数量，默认5，最大20")
                    }
                )
            },
            required = arrayOf("query")
        ),
        execute = { arguments ->
            val query = arguments.getStringValue("query")
            val group = arguments.getStringValue("group")
            val limit = arguments.getIntValue("limit", DEFAULT_LIMIT)
            if (query.isBlank()) {
                GSON.toJson(emptyList<Any>())
            } else {
                searchBooks(query, group, limit)
            }
        }
    )

    private val searchSourceRepositoryTool = AgentTool(
        name = "search_source_repository",
        description = "在阅读书源仓库中按网站域名、网址或名称搜索可导入的 Legado 3.X 书源。" +
            "当用户询问某网站有没有现成书源、要求查找或导入书源时，应先调用本工具；" +
            "返回的结果会在界面显示导入按钮，导入必须由用户点击确认",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "query",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "网站域名、网址或书源名称，例如 www.example.com")
                    }
                )
                add(
                    "limit",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "可选，返回数量，默认10，最大20")
                    }
                )
            },
            required = arrayOf("query")
        ),
        execute = { arguments ->
            val query = arguments.getStringValue("query")
            if (query.isBlank()) {
                GSON.toJson(emptyList<Any>())
            } else {
                searchSourceRepository(query, arguments.getIntValue("limit", 10))
            }
        }
    )

    private val addBookToShelfTool = AgentTool(
        name = "add_book_to_shelf",
        description = "将书籍加入书架，需先通过 search_books 搜索到该书并将结果的 bookUrl 传入",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "bookUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书籍地址，取自 search_books 结果的 bookUrl 字段")
                    }
                )
            },
            required = arrayOf("bookUrl")
        ),
        execute = { arguments ->
            addBookToShelf(arguments.getStringValue("bookUrl"))
        }
    )

    private val createBookSourceTool = AgentTool(
        name = "create_book_source",
        description = "编写Legado书源的入口。抓取网站首页HTML，创建书源草稿，并返回分步调试指引。" +
            "之后应依次调用 update_book_source 填写/修正规则、debug_source_search 调试搜索、" +
            "debug_source_book_info 调试详情、debug_source_toc 调试目录、debug_source_content 调试正文，" +
            "全部调试通过后调用 save_book_source 保存",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "url",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "网站首页地址，例如 https://www.example.com")
                    }
                )
            },
            required = arrayOf("url")
        ),
        execute = { arguments ->
            val url = arguments.getStringValue("url")
            if (url.isBlank()) {
                "书源网址为空"
            } else {
                createBookSource(url)
            }
        }
    )

    private val fetchPageTool = AgentTool(
        name = "fetch_page",
        description = "抓取指定网页并返回清理后的文本，用于分析网站结构、搜索表单、列表或正文的HTML特征，从而推导规则",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "url",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要访问的网页地址，可带Legado链接参数，如 url,{\"method\":\"POST\",\"body\":\"key=xxx\"}")
                    }
                )
                add(
                    "method",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，GET 或 POST，默认 GET")
                    }
                )
                add(
                    "body",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，POST 请求体")
                    }
                )
            },
            required = arrayOf("url")
        ),
        execute = { arguments ->
            val url = arguments.getStringValue("url")
            if (url.isBlank()) {
                "页面地址为空"
            } else {
                fetchPage(url, arguments.getStringValue("method"), arguments.getStringValue("body"))
            }
        }
    )

    private val updateBookSourceTool = AgentTool(
        name = "update_book_source",
        description = "用完整的书源JSON更新当前编辑中的书源草稿，返回更新后的草稿摘要。每次修正规则后都应调用本工具，然后再调试验证",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "sourceJson",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "完整的Legado书源JSON，需包含 bookSourceUrl，可包含 bookSourceName、searchUrl、ruleSearch、ruleBookInfo、ruleToc、ruleContent 等")
                    }
                )
            },
            required = arrayOf("sourceJson")
        ),
        execute = { arguments ->
            val sourceJson = arguments.getStringValue("sourceJson")
            if (sourceJson.isBlank()) {
                "书源JSON为空"
            } else {
                updateBookSource(sourceJson)
            }
        }
    )

    private val debugSourceSearchTool = AgentTool(
        name = "debug_source_search",
        description = "调试当前书源草稿的搜索：用草稿的 searchUrl 和 ruleSearch 搜索指定关键字。" +
            "成功返回解析出的书籍列表；失败返回搜索页HTML片段，据此修正 searchUrl 或 ruleSearch 后再次调试",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "key",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "搜索关键字，建议用一本真实存在的小说名")
                    }
                )
            },
            required = arrayOf("key")
        ),
        execute = { arguments ->
            val key = arguments.getStringValue("key")
            if (key.isBlank()) {
                "搜索关键字为空"
            } else {
                debugSourceSearch(key)
            }
        }
    )

    private val debugSourceBookInfoTool = AgentTool(
        name = "debug_source_book_info",
        description = "调试当前书源草稿的详情页：用草稿的 ruleBookInfo 解析书籍详情。" +
            "成功返回书名、作者、简介、封面、目录地址；失败返回详情页HTML片段，据此修正 ruleBookInfo",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "bookUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书籍详情页地址，取自 debug_source_search 结果的 bookUrl")
                    }
                )
            },
            required = arrayOf("bookUrl")
        ),
        execute = { arguments ->
            val bookUrl = arguments.getStringValue("bookUrl")
            if (bookUrl.isBlank()) {
                "详情页地址为空"
            } else {
                debugSourceBookInfo(bookUrl)
            }
        }
    )

    private val debugSourceTocTool = AgentTool(
        name = "debug_source_toc",
        description = "调试当前书源草稿的目录页：用草稿的 ruleToc 解析章节列表。" +
            "成功返回前若干章节的标题和地址；失败返回目录页HTML片段，据此修正 ruleToc",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "tocUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "目录页地址，取自 debug_source_search 或 debug_source_book_info 结果的 tocUrl")
                    }
                )
                add(
                    "bookUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，书籍详情页地址，作为相对链接的基准，省略时使用 tocUrl")
                    }
                )
            },
            required = arrayOf("tocUrl")
        ),
        execute = { arguments ->
            val tocUrl = arguments.getStringValue("tocUrl")
            if (tocUrl.isBlank()) {
                "目录页地址为空"
            } else {
                debugSourceToc(tocUrl, arguments.getStringValue("bookUrl"))
            }
        }
    )

    private val debugSourceContentTool = AgentTool(
        name = "debug_source_content",
        description = "调试当前书源草稿的正文页：用草稿的 ruleContent 解析章节正文。" +
            "成功返回正文预览；失败返回正文页HTML片段，据此修正 ruleContent",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "chapterUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "章节正文地址，取自 debug_source_toc 结果的章节 url")
                    }
                )
                add(
                    "bookUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，书籍详情页地址，作为相对链接的基准")
                    }
                )
                add(
                    "tocUrl",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，目录页地址，作为相对链接的基准")
                    }
                )
            },
            required = arrayOf("chapterUrl")
        ),
        execute = { arguments ->
            val chapterUrl = arguments.getStringValue("chapterUrl")
            if (chapterUrl.isBlank()) {
                "章节地址为空"
            } else {
                debugSourceContent(
                    chapterUrl,
                    arguments.getStringValue("bookUrl"),
                    arguments.getStringValue("tocUrl")
                )
            }
        }
    )

    private val saveBookSourceTool = AgentTool(
        name = "save_book_source",
        description = "校验并保存当前书源草稿。依次验证搜索、详情、目录、正文，全部通过后保存到AI生成分组。失败时返回具体原因，可继续修正规则后重试",
        parameters = objectParameters(
            properties = JsonObject(),
            required = arrayOf()
        ),
        execute = { saveBookSource() }
    )

    private val readingReportTool = AgentTool(
        name = "reading_report",
        description = "生成阅读周报或月报，返回统计周期内的阅读时长和书籍列表",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "period",
                    JsonObject().apply {
                        addProperty("type", "string")
                        add(
                            "enum",
                            JsonArray().apply {
                                add("week")
                                add("month")
                            }
                        )
                        addProperty("description", "统计周期：week 表示本周，month 表示本月")
                    }
                )
            },
            required = arrayOf("period")
        ),
        execute = { arguments ->
            val period = arguments.getStringValue("period")
            if (period != "week" && period != "month") {
                "统计周期无效，请使用 week 或 month"
            } else {
                readingReport(period)
            }
        }
    )

    private val libraryStatsTool = AgentTool(
        name = "library_stats",
        description = "查询当前书源数量、订阅源数量、书源分组及各组数量、书籍总数、书架分组及各组书籍数量",
        parameters = objectParameters(
            properties = JsonObject(),
            required = arrayOf()
        ),
        execute = { getLibraryStats() }
    )

    private val createAiBookTool = AgentTool(
        name = "create_ai_book",
        description = "根据用户提供的小说类型和主题自动创作一部完整小说：生成书名简介、创建书籍并加入书架、生成章节大纲、逐章编写正文并保存，可指定章节数和每章字数",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "type",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "小说类型，例如：科幻、玄幻、都市、悬疑")
                    }
                )
                add(
                    "theme",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "主题或核心设定描述，例如：星空冒险、废土求生")
                    }
                )
                add(
                    "chapterCount",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "可选，章节数量，默认10，最大50")
                    }
                )
                add(
                    "wordsPerChapter",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "可选，每章目标字数，默认1500")
                    }
                )
            },
            required = arrayOf("type", "theme")
        ),
        execute = { arguments ->
            createAiBook(
                arguments.getStringValue("type"),
                arguments.getStringValue("theme"),
                arguments.getIntValue("chapterCount", 10),
                arguments.getIntValue("wordsPerChapter", 1500)
            )
        }
    )

    private val readBookContentTool = AgentTool(
        name = "read_book_content",
        description = "按书名(可附作者，逗号分隔)或bookUrl定位书架上的书籍，读取从 startIndex(章节号，从0开始)起连续 count 章的正文。" +
            "返回书籍信息、章节标题与正文全文。本地已缓存优先，网络书未缓存时自动联网抓取。count 默认1，最大20。maxChars 可选，用于限制返回正文长度",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "book",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书名；可附作者，格式如\"书名,作者\"；也可直接传 bookUrl")
                    }
                )
                add(
                    "startIndex",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "起始章节号，从0开始，0表示第一章")
                    }
                )
                add(
                    "count",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "连续读取的章节数，默认1，最大20")
                    }
                )
                add(
                    "maxChars",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "可选，返回正文的长度上限，0或省略表示不截断")
                    }
                )
            },
            required = arrayOf("book", "startIndex")
        ),
        execute = { arguments ->
            val book = arguments.getStringValue("book")
            if (book.isBlank()) {
                "书名或书籍地址为空"
            } else {
                readBookContent(
                    book,
                    arguments.getIntValue("startIndex", 0),
                    arguments.getIntValue("count", 1),
                    arguments.getIntValue("maxChars", 0)
                )
            }
        }
    )

    private val allTools = listOf(
        searchBooksTool,
        searchSourceRepositoryTool,
        addBookToShelfTool,
        createBookSourceTool,
        fetchPageTool,
        updateBookSourceTool,
        debugSourceSearchTool,
        debugSourceBookInfoTool,
        debugSourceTocTool,
        debugSourceContentTool,
        saveBookSourceTool,
        readingReportTool,
        libraryStatsTool,
        createAiBookTool,
        readBookContentTool
    )
    private val toolMap = allTools.associateBy { it.name }

    fun find(name: String): AgentTool? = toolMap[name]

    /**
     * 生成工具清单摘要，用于注入系统提示词，与工具注册表保持单一事实来源
     */
    fun overview(): String =
        allTools.joinToString("；") { "${it.name}：${it.description}" }

    fun toJsonArray(): JsonArray = JsonArray().apply {
        allTools.forEach {
            val toolJson = it.toJson()
            sanitizeToolSchema(toolJson)
            add(toolJson)
        }
    }

    /**
     * 部分网关（如 OpenCode Go / Console Go）对工具 schema 中的 enum、minimum、maximum、
     * pattern、format 等关键字校验严格，会被拒绝并返回误导性的错误（如 "Model is not supported"）。
     * 发送前移除这些关键字，保证工具 schema 尽量朴素。
     */
    private val sanitizeKeywords = setOf(
        "enum", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "pattern", "format"
    )

    private fun sanitizeToolSchema(element: JsonElement) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            sanitizeKeywords.forEach { obj.remove(it) }
            obj.entrySet().forEach { (_, value) ->
                sanitizeToolSchema(value)
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { sanitizeToolSchema(it) }
        }
    }

    private fun objectParameters(
        properties: JsonObject,
        required: Array<String>
    ): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        add("required", JsonArray().apply { required.forEach { add(it) } })
    }

    private fun JsonObject.getStringValue(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.getIntValue(key: String, default: Int): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default
}
