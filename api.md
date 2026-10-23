# 阅读 API

本文依据当前仓库实现整理，更新日期：2026-10-23。

- HTTP 路由：[HttpServer.kt](app/src/main/java/io/legado/app/web/HttpServer.kt)
- WebSocket 路由：[WebSocketServer.kt](app/src/main/java/io/legado/app/web/WebSocketServer.kt)
- 业务实现：[controller](app/src/main/java/io/legado/app/api/controller/)
- Android 跨应用接口：[ReaderProvider.kt](app/src/main/java/io/legado/app/api/ReaderProvider.kt)

本文只说明 App 对外部数据的通用接口，不提供实际书源、站点脚本或调试材料。

## 连接与通用约定

在 App 中启用“Web 服务”，以 App 显示的地址为准。默认 HTTP 端口为 `1122`，WebSocket 端口为 HTTP 端口加一，即 `1123`。配置端口不在 `1024..65530` 时回退到 `1122`。

下文路径默认相对于 `http://127.0.0.1:1122`；WebSocket 相对于 `ws://127.0.0.1:1123`。`127.0.0.1` 指向调用方自身，从电脑访问手机时应替换为手机 IP，或使用已配置的端口转发。

- 除本地书籍上传外，POST 请求体使用 JSON，建议设置 `Content-Type: application/json; charset=utf-8`。
- 查询参数中的 URL、文件路径、中文等应进行 URL 编码。
- 当前 HTTP/WebSocket 路由没有登录或令牌校验。
- HTTP 普通业务响应采用 [ReturnData](app/src/main/java/io/legado/app/api/ReturnData.kt)；图片及 WebSocket 消息例外。

成功响应示例：

```json
{"isSuccess":true,"errorMsg":"","data":[]}
```

失败响应示例：

```json
{"isSuccess":false,"errorMsg":"参数url不能为空，请指定书籍地址"}
```

`data` 类型由接口决定，空值字段可能被省略。应检查 `isSuccess`，不能仅凭 HTTP 200 判断业务成功。普通 JSON 响应未统一声明 `application/json`，客户端应显式解析响应体。未捕获异常可能返回纯文本；未匹配的路由会转入静态资源处理，不保证返回上述 JSON 结构。

## HTTP 接口

### 书源与订阅源

数据结构分别参考 [BookSource.kt](app/src/main/java/io/legado/app/data/entities/BookSource.kt) 和 [RssSource.kt](app/src/main/java/io/legado/app/data/entities/RssSource.kt)。下表中的对象和数组均指 JSON 请求体或响应中的 `data`。

| 方法 | 路径 | 请求 | 返回数据 |
| --- | --- | --- | --- |
| POST | `/saveBookSource` | 单个 `BookSource` 对象 | 空字符串 |
| POST | `/saveBookSources` | `BookSource[]` | 实际保存的对象数组 |
| POST | `/deleteBookSources` | `BookSource[]` | `"已执行"` |
| GET | `/getBookSource` | 查询参数 `url`：书源地址 | `BookSource` |
| GET | `/getBookSources` | 无 | `BookSource[]` |
| GET | `/getBookSourceGroups` | 无 | 已启用书源的分组名称 `string[]`，去重并排序 |
| POST | `/saveRssSource` | 单个 `RssSource` 对象 | 空字符串 |
| POST | `/saveRssSources` | `RssSource[]` | 实际保存的对象数组 |
| POST | `/deleteRssSources` | `RssSource[]` | `"已执行"` |
| GET | `/getRssSource` | 查询参数 `url`：订阅源地址 | `RssSource` |
| GET | `/getRssSources` | 无 | `RssSource[]` |

单条保存要求名称和地址非空。批量保存要求非空数组，并跳过名称或地址为空的条目。获取全部书源或订阅源时，若设备列表为空，当前实现返回业务失败。

### 书架与阅读

数据结构参考 [Book.kt](app/src/main/java/io/legado/app/data/entities/Book.kt)、[BookChapter.kt](app/src/main/java/io/legado/app/data/entities/BookChapter.kt) 和 [BookProgress.kt](app/src/main/java/io/legado/app/data/entities/BookProgress.kt)。

| 方法 | 路径 | 请求 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/getBookshelf` | 无 | 按 App 书架排序设置排序的 `Book[]`；没有书籍时返回业务失败 |
| POST | `/saveBook` | 单个 `Book` 对象 | 空字符串 |
| POST | `/deleteBook` | 单个 `Book` 对象 | 空字符串 |
| GET | `/getChapterList` | 查询参数 `url`：书籍的 `bookUrl` | `BookChapter[]`；无缓存目录时自动刷新 |
| GET | `/refreshToc` | 查询参数 `url`：书籍的 `bookUrl` | 刷新并保存后的 `BookChapter[]` |
| GET | `/getBookContent` | 查询参数 `url`、`index` | 处理后的正文字符串，不含标题 |
| POST | `/saveBookProgress` | 单个 `BookProgress` 对象 | 空字符串 |
| POST | `/addLocalBook` | `multipart/form-data`，见下文 | `true` |

在线搜索或发现的书籍应先通过 `/saveBook` 保存，再获取目录和正文；试读结束后可通过 `/deleteBook` 删除。`index` 是从 `0` 开始的章节索引，建议使用目录返回的 `index`。正文优先读取本地缓存，没有缓存时由 App 获取。

保存进度按 `name` 和 `author` 查找书籍，示例：

```json
{
  "name": "示例书籍",
  "author": "示例作者",
  "durChapterIndex": 0,
  "durChapterPos": 0,
  "durChapterTime": 1792713600000,
  "durChapterTitle": "第一章"
}
```

`durChapterTime` 为 Unix 毫秒时间戳。

本地书籍上传使用两个表单字段：`fileName` 为文件名，`fileData` 为文件内容。示例：

```sh
curl -F "fileName=example.txt" -F "fileData=@example.txt" http://127.0.0.1:1122/addLocalBook
```

文件由 App 的本地书籍导入器处理；若提示需要重新设置书籍保存位置，应先在 App 中配置保存目录。

### 书架分组

| 方法 | 路径 | 请求 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/getBookGroups` | 无 | 可显示的分组数组：`{groupId, groupName, bookUrls}` |
| GET | `/getBookGroup` | 查询参数 `url`：书籍地址 | `{groups, selectedGroupIds}` |
| POST | `/saveBookGroups` | `{bookUrl, groupIds}` | 保存后的 `{groups, selectedGroupIds}` |

`groups` 为可编辑的用户分组数组，每项包含 `groupId` 和 `groupName`。`selectedGroupIds` 为当前已选 ID 数组。内置分组由 App 计算，不能通过保存接口指定。

所有分组 ID 均为十进制**字符串**，避免 JavaScript 丢失 64 位整数精度。保存会替换这本书的用户分组，传空数组可清除用户分组，且不会修改阅读进度或设置：

```json
{"bookUrl":"<书籍地址>","groupIds":["1","4"]}
```

ID 必须来自当前分组选项；书籍已离开书架或分组已失效时返回业务失败。返回结构实现见 [BookshelfGroups.kt](app/src/main/java/io/legado/app/api/controller/BookshelfGroups.kt)。

### 发现

| 方法 | 路径 | 查询参数 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/getExploreSources` | 无 | 已启用、启用发现且具有发现地址的书源摘要数组：`{url, name, group}` |
| GET | `/getExploreKinds` | `sourceUrl`：书源地址 | 分类数组，包含 `title`、可选 `url`、可选 `style` |
| GET | `/exploreBooks` | `sourceUrl`、`url`：分类地址、`page`：页码 | `Book[]` |

先获取书源摘要，再获取其分类。`/exploreBooks` 的 `url` 必须匹配该书源返回的分类地址，`page` 必须为大于等于 `1` 的整数。分类可能仅有标题，调用前应确认有可用地址。分类和书籍加载的服务端超时为 60 秒。

### 封面与正文图片

| 方法 | 路径 | 查询参数 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/cover` | `path`：封面地址或图片路径 | 原始图片文件，保留动画帧 |
| GET | `/image` | `url`：书籍地址，`path`：正文图片地址，`width`：可选整数，默认 `640` | PNG 图片 |

图片成功响应直接返回二进制内容，不使用 `ReturnData` 包装。加载失败时可能返回业务错误 JSON；响应内容类型不是图片时，应尝试解析错误信息。

`/cover` 按文件内容生成 `ETag`，缓存策略为 `private, max-age=86400`；携带匹配的 `If-None-Match` 时返回 HTTP 304，失败响应使用 `no-store`。`/image` 由 App 处理书源请求头、缓存及图片解密。

### Web 阅读配置

| 方法 | 路径 | 请求 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/getReadConfig` | 无 | **JSON 字符串**，需再解析一次 |
| POST | `/saveReadConfig` | 阅读配置 JSON 对象 | 空字符串 |

配置字段参考 [web.d.ts](modules/web/src/web.d.ts)：`theme`、`font`、`fontSize`、`readWidth`、`infiniteLoading`、`mangaMode`、`customFontName`、`jumpDuration` 和 `spacing`（`paragraph`、`line`、`letter`）。

`/getReadConfig` 总会注入 App 当前主题的 `appAccentColor`（如 `#3F51B5`）。此字段为只读，保存时应移除。没有已保存配置时仍会返回包含强调色的 JSON 字符串，其余默认值由网页端补齐。

### 替换规则

规则结构参考 [ReplaceRule.kt](app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。

| 方法 | 路径 | 请求 | 返回数据或当前行为 |
| --- | --- | --- | --- |
| GET | `/getReplaceRules` | 无 | 规则数组序列化后的 **JSON 字符串**，需再解析一次 |
| POST | `/saveReplaceRule` | 单个 `ReplaceRule` 对象 | 保存规则，见下方兼容性说明 |
| POST | `/deleteReplaceRule` | 单个 `ReplaceRule` 对象 | 删除规则，见下方兼容性说明 |
| POST | `/testReplaceRule` | `{"rule": <ReplaceRule对象>, "text": "待替换文本"}` | 替换后的文本；替换执行异常时可能为异常堆栈字符串 |

`/testReplaceRule` 的 `rule` 也兼容规则对象序列化后的 JSON 字符串。

当前 `/saveReplaceRule` 和 `/deleteReplaceRule` 在数据库操作完成后未调用 `setData`，因此仍返回默认的 `isSuccess: false` 和错误提示。接入时应注意这一现状，可重新查询规则确认结果。

### AI 对话

AI 供应商和角色在 App 中配置。Web 接口传输对话状态，供应商凭据、请求头和实际模型请求保留在 App 中。AI 接口响应均设置 `Cache-Control: no-store`。

| 方法 | 路径 | 用途 | 返回数据 |
| --- | --- | --- | --- |
| GET | `/getAiChatConfig` | 获取已启用供应商、模型列表及角色 | `{suppliers, personas, supplierId, personaId}` |
| GET | `/getAiChat` | 获取当前对话快照 | 对话状态对象 |
| GET | `/getAiChatHistory` | 获取对话历史列表 | `[{id, title, updatedAt}]` |
| POST | `/aiChat` | 执行对话操作 | 操作后的对话状态对象 |

配置中的供应商项包含 `id`、`name`、`model`、`models` 和可选 `modelError`；角色项包含 `id`、`name`。供应商、角色 ID 均为字符串，角色 ID `"0"` 表示默认角色。获取模型列表每个供应商最多等待 15 秒，失败时回退到已保存模型，并返回 `modelError`。

#### 对话操作

请求体中的 `action` 为必填字符串：

| action | 其他字段 | 行为 |
| --- | --- | --- |
| `send` | `text`：消息；可选 `attachments` 数组 | 发送文本或附件 |
| `cancel` | 无 | 停止当前生成 |
| `new` | 无 | 新建对话 |
| `open` | `id`：历史对话 ID | 打开已存在对话 |
| `supplier` | `id`：供应商 ID；可选 `model`：模型名称 | 切换已启用供应商或模型 |
| `persona` | `id`：角色 ID | 切换角色，`"0"` 为默认角色 |
| `more` | 无 | 继续搜索更多结果 |

发送示例：

```json
{"action":"send","text":"请推荐一本科幻小说"}
```

消息去除首尾空白后不能超过 `32000` 个字符；没有文本时必须提供附件，App 会补充“请分析这些附件。”。发送前必须选好可用供应商和模型。生成期间不能再次发送、切换供应商或角色、继续搜索。

选择模型前应先调用 `/getAiChatConfig`；模型名称必须在该供应商最近获取的模型列表中，或等于供应商已保存的默认模型。

#### 附件

附件随 `send` 请求发送，每项为 `{name, dataUrl}`，其中 `dataUrl` 使用 `data:<MIME>;base64,<内容>`。这是 JSON 请求，不使用文件上传表单。

```json
{
  "action": "send",
  "text": "请分析附件",
  "attachments": [
    {"name": "notes.txt", "dataUrl": "data:text/plain;base64,SGVsbG8="}
  ]
}
```

- 每条消息最多 `4` 个附件，解码后总大小最多 `10 MiB`。
- 支持 PNG、JPEG、GIF、WebP，单张最多 `5 MiB`，按文件头识别图片类型。
- 文本必须为有效 UTF-8，不能含空字符，单个最多 `256 KiB`。
- 文本扩展名支持 `txt`、`md`、`csv`、`json`、`log`、`xml`、`html`、`htm`、`yaml`、`yml`、`js`、`ts`、`py`、`kt`、`java`、`css`、`sql`。
- `/aiChat` 还会检查整个请求体字符串长度，上限为 `15 * 1024 * 1024`，包含 Base64 和其他 JSON 内容。

#### 对话状态

状态对象字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversationId` | string 或 null | 当前对话 ID，空值可能省略 |
| `waiting` | boolean | 是否正在生成或执行任务 |
| `supplierId` | string | 当前供应商 ID |
| `model` | string | 当前选定模型 |
| `personaId` | string | 当前角色 ID |
| `messages` | array | 当前对话消息 |

每条消息包含：

- `isUser`、`text`、`streaming`、`canLoadMore`：消息角色、正文、流式状态、是否可继续搜索。
- `steps`：`{id, title, state, summary}` 数组，`state` 为 `RUNNING`、`DONE` 或 `FAILED`。
- `books`：`{name, author, originName}` 数组。
- `images`：`{path, prompt}` 数组。
- `attachments`：`{name, mimeType, size, imagePath}` 数组；`size` 为字节数，`imagePath` 仅图片附件提供，可通过 `/cover?path=...` 展示。

可选空值字段可能省略。接口没有独立的 AI WebSocket 或 SSE 通道；发送后轮询 `/getAiChat` 获取增量正文和步骤，直到 `waiting` 为 `false`。停止 Web 服务会取消其正在进行的 AI 请求和后台任务。

## WebSocket 接口

WebSocket 文本消息不使用 HTTP 的 `ReturnData` 包装。

### 搜索在线书籍

连接 `ws://127.0.0.1:1123/searchBook` 后发送 JSON：

```json
{"key":"搜索关键词","group":""}
```

`key` 必须非空。`group` 为可选搜索范围：

- 空字符串：全部已启用书源。
- 分组名称，多个分组以英文逗号分隔。
- 单个书源范围：`书源名称::书源地址`。
- 省略时使用 App 当前搜索范围。

指定分组不存在或范围内没有书源时，不会回退到全部书源。服务端分批发送 [SearchBook](app/src/main/java/io/legado/app/data/entities/SearchBook.kt) 数组的 JSON 文本，搜索完成后关闭连接，正常关闭原因是 `Search finish`；错误消息可能为纯文本。

### 通用源调试通道

| 路径 | 发送的 JSON 字段 | 返回 |
| --- | --- | --- |
| `/bookSourceDebug` | `tag`：已导入书源的地址；`key`：非空调试输入 | 调试日志文本 |
| `/rssSourceDebug` | `tag`：已导入订阅源的地址；不要求 `key` | 调试日志文本 |

这些通道使用 App 已导入的数据；日志逐条返回，调试结束后关闭连接。实际源的编写、修改和调试材料按仓库约定仅保留在本地忽略目录。

## Android Content Provider

接口入口为 `content://<providerHost>`，其中 `providerHost` 是当前安装包的 `applicationId` 加 `.readerProvider`，例如调试包为 `io.legado.app.debug.readerProvider`。以实际包名和 [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 为准。

当前清单导出 `ReaderProvider`，未声明或绑定旧文档所述的 `io.legado.READ_WRITE` 权限。Content Provider 调用不需要启用 Web 服务。

### 调用方式

- 插入：`ContentResolver.insert(uri, values)`，在 `ContentValues` 的 `"json"` 键中放入 JSON 字符串。当前 `insert` 返回 `null`，不能据此判断业务是否成功。
- 查询：`ContentResolver.query(...)` 返回单列 `result` 的 Cursor。先调用 `moveToFirst()`，再用 `getString(0)` 读取 `ReturnData` JSON，使用后关闭 Cursor。
- 删除：将待删除对象数组的 JSON 字符串放在 `ContentResolver.delete(uri, selection, null)` 的 **selection 参数**，不使用 `ContentValues`。当前匹配路径执行后返回 `0`，不是删除数量。
- 未匹配 URI 的 `insert`、`query` 返回 `null`，`delete` 返回 `-1`；`update`、`getType` 未实现。

### 当前可用路径

| 调用方法 | URI 路径 | 参数或数据 |
| --- | --- | --- |
| insert | `/bookSource/insert` | `json`：单个 `BookSource` 对象 |
| insert | `/bookSources/insert` | `json`：`BookSource[]` |
| delete | `/bookSources/delete` | `selection`：`BookSource[]` JSON 字符串 |
| query | `/bookSource/query` | 查询参数 `url`：书源地址 |
| query | `/bookSources/query` | 无 |
| insert | `/book/insert` | `json`：单个 `Book` 对象 |
| query | `/books/query` | 无，获取书架 |
| query | `/book/refreshToc/query` | 查询参数 `url`：书籍地址 |
| query | `/book/chapter/query` | 查询参数 `url`：书籍地址 |
| query | `/book/content/query` | 查询参数 `url`、`index`，章节索引从 `0` 开始 |
| query | `/book/cover/query` | 查询参数 `path`：封面地址或路径 |

Provider 查询复用相应控制器，但封面调用的是 `getCover`：Bitmap 被装入 `ReturnData` 后序列化为 JSON，不能按 HTTP `/cover` 的原始图片流读取。

### 当前实现限制

`ReaderProvider` 虽注册了以下订阅源路径，但它们目前错误映射到书源请求码，实际调用 `BookSourceController`：

- `/rssSource/insert`、`/rssSources/insert`
- `/rssSources/delete`
- `/rssSource/query`、`/rssSources/query`

因此这些路径不能作为正确的订阅源接口使用，订阅源管理应使用上述 HTTP 接口。

`SaveBookProgress` 虽有内部处理分支，但没有注册 URI，目前不能通过 Provider 调用。书籍删除、书架分组、发现、阅读配置、替换规则及 AI 对话也未提供 Provider 路由。
