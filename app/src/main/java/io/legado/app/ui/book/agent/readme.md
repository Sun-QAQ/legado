# AI Agent 目录说明

本目录实现 AI 对话、工具调用，以及 AI 供应商、人格、联网搜索和图片生成配置。以下按功能列出每个 Kotlin 文件的主要职责。

## 对话与会话管理

| 文件 | 作用 |
| --- | --- |
| `AgentFragment.kt` | 主界面的 AI 对话入口，处理消息输入、供应商与人格选择、菜单操作和输入框避让键盘。 |
| `AgentViewModel.kt` | 对话核心逻辑，管理提示词、流式请求、工具执行与会话持久化，并维护供应商和人格选择状态。 |
| `AgentSystemPrompt.kt` | 按预设、当前人格、通用工具规则和工具清单组合系统提示词，约束工具调用后的回复继续保持当前人格。 |
| `AgentAdapter.kt` | 展示用户消息和 AI 回复，渲染 Markdown、执行步骤、书籍、书源仓库结果及生成图片。 |
| `AgentMessage.kt` | 定义对话消息数据，包括文本、书籍、执行步骤、图片及流式显示状态。 |
| `AgentConversationEditor.kt` | 用消息 ID 关联可见消息和模型轮次，支持修改、删除及旧会话关联补齐。 |
| `AgentMessageEditDialog.kt` | Android 消息长按菜单的多行编辑弹窗，保存正文但不自动重发。 |
| `AgentStep.kt` | 定义执行步骤及其运行、成功、失败状态，记录详情、摘要和耗时。 |
| `AgentConversationCodec.kt` | 定义模型对话轮次，负责消息和轮次的 JSON 编解码及会话标题生成。 |
| `AgentConversationAdapter.kt` | 展示历史会话列表，并转发打开、改名和删除操作。 |
| `AgentConversationHistoryDialog.kt` | 提供历史会话弹窗，支持打开、修改名称、单条删除和清空历史。 |
| `AgentRequestTracker.kt` | 跟踪请求协程，支持取消并等待结束，避免旧请求影响切换后的会话。 |
| `AgentLogMetadata.kt` | 生成请求诊断日志元数据，记录模型、请求大小、工具名称、状态码和耗时，不记录正文。 |
| `StreamingToolCallAccumulator.kt` | 合并流式响应中分段返回的工具调用名称和参数，组装完整调用数据。 |

Android 对话中，用户消息、AI 回复、附件和结果卡片均可长按修改或删除。修改只更新所选消息正文，保留附件和其他消息，同时同步模型上下文；删除 AI 回复会移除其关联的隐藏工具调用、工具结果与内部继续提示。正在生成时需先停止回复。消息和模型轮次的关联随会话 JSON 保存，旧记录恢复时补齐；删除最后一条消息会删除该空会话快照，避免重开后恢复被删内容。保存按会话序号合并，旧的异步快照不会覆盖更新后的消息。

历史对话可点击铅笔按钮或长按条目修改名称，名称不能为空，最多 80 个字符。修改后立即更新列表，继续聊天、编辑消息及重新打开会话均保留该名称；自动保存会在事务中保留数据库中的最新名称，避免旧快照覆盖改名结果。

## 工具与阅读能力

| 文件 | 作用 |
| --- | --- |
| `AgentTool.kt` | 定义工具执行上下文接口，以及工具名称、描述、参数 schema、执行函数和 JSON 转换。 |
| `AgentTools.kt` | 注册全部可调用工具，统一提供工具查找、参数 schema、提示词摘要和启用状态信息。 |
| `AgentToolPreferences.kt` | 持久化工具启停设置，读取被禁用的工具名称集合。 |
| `AiToolAdapter.kt` | 展示工具名称、描述及启用开关，并转发开关操作。 |
| `AiToolManageActivity.kt` | 工具管理页面，加载工具列表并保存启停设置。 |
| `ReadingContextProvider.kt` | 获取书籍目录和章节正文，供 AI 章节读取工具使用。 |
| `ReadingReport.kt` | 汇总本周或本月的阅读时长、阅读天数及书籍记录，生成供 AI 使用的 JSON 数据。 |
| `LibraryStats.kt` | 汇总书架书籍、书源、订阅源及分组数量，生成书库统计工具的 JSON 结果。 |
| `SourceRepository.kt` | 搜索在线书源仓库、解析结果条目，并根据书源 ID 构造下载地址。 |

## AI 供应商配置

| 文件 | 作用 |
| --- | --- |
| `AiSourceManageActivity.kt` | AI 供应商管理页面，支持新增、编辑、启停、删除、获取模型及选择供应商和模型。 |
| `AiSourceManageViewModel.kt` | 处理供应商的数据库更新、模型列表获取及当前供应商和模型选择。 |
| `AiSourceAdapter.kt` | 展示供应商列表、当前选择和启用状态，并提供条目操作菜单。 |
| `AiSourceEditDialog.kt` | 编辑供应商连接参数，支持供应商预设、模型获取和输入校验。 |
| `AiSourceEditViewModel.kt` | 加载、保存供应商配置，并为编辑界面获取可用模型。 |
| `AiSourceHelper.kt` | 请求供应商的模型列表接口，解析可用模型名称。 |

## AI 人格配置

人格设定与通用阅读、工具规则独立组合；自定义人格不会混入默认阅读助手身份和回答风格。每轮工具调用继续携带当前人格，读后感和评价应以该人格的视角表达，同时区分正文事实与个人感受。

| 文件 | 作用 |
| --- | --- |
| `AiPersonaManageActivity.kt` | AI 人格管理页面，支持新增、编辑、选择和删除人格。 |
| `AiPersonaManageViewModel.kt` | 维护当前人格选择，并处理人格删除与选择失效后的回退。 |
| `AiPersonaAdapter.kt` | 展示人格列表及当前选中状态，并提供编辑、选择和删除操作。 |
| `AiPersonaEditDialog.kt` | 编辑人格名称与提示词，并校验和提交输入。 |
| `AiPersonaEditViewModel.kt` | 从数据库加载人格配置并保存编辑结果。 |

## 联网搜索配置

| 文件 | 作用 |
| --- | --- |
| `AiSearchSourceManageActivity.kt` | 联网搜索源管理页面，支持新增、编辑、启停、删除、设置默认源和测试搜索。 |
| `AiSearchSourceManageViewModel.kt` | 更新搜索源配置，维护默认搜索源及失效后的回退。 |
| `AiSearchSourceAdapter.kt` | 展示搜索源类型、启用与默认状态，并转发条目操作。 |
| `AiSearchSourceEditDialog.kt` | 编辑搜索源类型、接口及认证参数，支持预设与自定义请求、响应解析配置。 |
| `AiSearchSourceEditViewModel.kt` | 加载并保存搜索源配置。 |
| `AgentSearchSourceSelection.kt` | 保存当前搜索源 ID，在所选源不可用时回退到首个启用源。 |
| `AiWebSearchHelper.kt` | 构造并发送联网搜索请求，处理不同搜索服务及自定义接口，将响应统一为搜索结果。 |

## 图片生成配置与存储

| 文件 | 作用 |
| --- | --- |
| `AiImageSourceManageActivity.kt` | 图片生成源管理页面，支持新增、编辑、启停、删除和设置默认源。 |
| `AiImageSourceManageViewModel.kt` | 更新图片生成源配置，维护默认源及失效后的回退。 |
| `AiImageSourceAdapter.kt` | 展示图片生成源、启用与默认状态，并提供条目操作菜单。 |
| `AiImageSourceEditDialog.kt` | 编辑图片生成接口、模型、尺寸、响应格式及自定义请求参数。 |
| `AiImageSourceEditViewModel.kt` | 加载并保存图片生成源配置。 |
| `AgentImageSourceSelection.kt` | 保存当前图片生成源 ID，在所选源不可用时回退到首个启用源。 |
| `AiImageGenerationHelper.kt` | 按接口类型请求图片生成，解析 OpenAI URL 或 Base64 响应，下载图片并限制大小。 |
| `ComfyUiImageGenerationHelper.kt` | 校验和填充 ComfyUI API 工作流，提交任务、轮询历史并获取最终图片地址。 |
| `AgentGeneratedImage.kt` | 定义生成图片的本地路径和对应提示词。 |
| `AgentImageStorage.kt` | 按会话保存生成图片，支持删除单个会话图片或清空全部图片。 |

### ComfyUI 配置

在 AI 的图片生成设置中新增供应商，将「生图接口类型」设为 ComfyUI。
填写手机或模拟器可访问的服务根地址，例如 `http://192.168.1.10:8188`（也支持以 `/prompt` 结尾的地址及反向代理路径）。
服务需要允许来自该设备的连接。API Key 可留空；填写后使用 Bearer 认证，自定义请求头可覆盖认证。

在 ComfyUI 中导出 API 格式工作流，粘贴到工作流输入框。支持直接的节点映射，以及包含 `prompt` 节点映射的完整请求体；不支持普通画布 JSON。
将正向提示词节点的文本改为 `"{{prompt}}"`，也可以嵌入已有文本，例如 `"油画风格，{{prompt}}"`。
可将宽、高和种子分别设为 `"{{width}}"`、`"{{height}}"`、`"{{seed}}"`，完整占位符发送时会转为数值；种子每次随机生成。
不使用这些可选占位符时，沿用工作流中的尺寸及种子。模型和负面提示词等参数直接在工作流中配置。

调用使用 ComfyUI 原生 `/prompt` → `/history/{prompt_id}` → `/view` 流程，所有请求携带配置的认证头。
优先展示首张 `output` 图片，无最终输出时可使用预览图片。任务失败或完成后无图片会明确报错，最长等待 10 分钟。
取消对话会停止客户端等待，不会调用影响其他任务的服务端全局中断接口。
