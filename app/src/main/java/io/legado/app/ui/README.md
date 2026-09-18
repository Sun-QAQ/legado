# 放置与界面有关的类

* about 关于界面
* association 导入书源界面
* book\audio 音频播放界面
* book\agent AI 对话与 Agent 工具界面
* book\arrange 书架整理界面
* book\info 书籍信息查看
* book\read 书籍阅读界面
* book\search 搜索书籍界面
* book\source 书源界面
* book\changeCover 封面换源界面
* book\changeSource 换源界面
* book\toc 目录界面
* book\download 下载界面
* book\explore 发现界面
* book\local 书籍导入界面
* document 文件选择界面
* config 配置界面
* main 主界面
* qrCode 二维码扫描界面
* replaceRule 替换净化界面
* rss\article 订阅条目界面
* rss\read 订阅阅读界面
* rss\source 订阅源界面
* welcome 欢迎界面
* widget 自定义插件

## book\agent

`book\agent` 提供 AI 对话、历史记录、人格、模型供应商、图像生成、网络搜索及工具管理能力。

主要入口：

* `AgentFragment.kt`：AI 对话界面和右上角功能菜单。
* `AgentViewModel.kt`：对话状态、流式请求、工具调用循环及书源创建实现。
* `AgentTool.kt`、`AgentTools.kt`：工具接口、参数 Schema 和工具注册表。
* `AgentToolPreferences.kt`：保存工具启用状态；禁用工具不会发送给模型，也不能在本地执行。
* `AiSourceManageActivity.kt`：管理对话模型供应商及当前模型。
* `AiPersonaManageActivity.kt`：管理并选择当前人格。
* `AiImageSourceManageActivity.kt`：管理图像生成供应商。
* `AiSearchSourceManageActivity.kt`：管理 Tavily、Brave、SearXNG 或自定义搜索接口。
* `AiToolManageActivity.kt`：查看、启用或禁用当前 Agent 工具。
* `AgentConversationHistoryDialog.kt`：查看、恢复和删除历史对话。

AI 创建书源由 `AgentTools.kt` 注册工具，具体逻辑位于 `AgentViewModel.kt`。标准流程为：

`create_book_source` → `update_book_source` → `debug_source_search` → `debug_source_book_info` → `debug_source_toc` → `debug_source_content` → `save_book_source`

创建中的书源先以禁用草稿保存于内存，各项规则调试和校验通过后才写入数据库，并归入“AI生成”分组。
