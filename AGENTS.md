# 仓库协作指南

## 项目范围与书源边界

本项目是阅读 App（Legado），包含 Android 客户端和 Vue 网页端。以下书源边界自 2026-10-22 起适用：

- 实际书源全部由外部提供，不将具体书源、站点脚本、规则、调试材料或其说明作为 App 项目交付内容。
- 所有书源相关的编写、调试、修改均只保留在本地，不创建 Git 提交，也不使用 `git add -f` 绕过忽略规则。
- 本地书源材料统一放在已忽略的 `docs/book-sources/` 或 `book-sources/` 中；临时调试文件放在已忽略的 `.tmp/` 中。不得放入 App 资源、测试或其他受跟踪目录。
- App 对外部书源的通用导入、编辑、解析等功能属于 App 代码，可按正常流程维护；这不授权提交任何实际书源。
- 外部书源维护不触发 App 的提交、构建和安装流程。仅调整上述本地规则或 Git 忽略配置时，也不触发提交、调试包构建和模拟器安装。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `app/src/main/java/io/legado/app/` | Android 客户端代码；`ui/` 存放界面和视图模型（ViewModel），`data/` 负责数据持久化，`model/` 负责解析，`web/` 实现内嵌服务器 |
| `app/src/main/res/` | Android 资源 |
| `app/src/main/assets/` | 随应用打包的静态资源 |
| `app/src/test/` | JVM 单元测试 |
| `app/src/androidTest/` | 设备测试 |
| `app/schemas/` | Room 数据库结构文件 |
| `modules/book/`、`modules/rhino/` | Android 库模块 |
| `modules/web/src/` | Vue、TypeScript 网页端代码 |
| `modules/web/public/` | 网页端静态资源 |
| `gradle/libs.versions.toml` | 集中管理依赖版本 |

## 开发环境与常用命令

### Android 客户端

使用 JDK 17 和 Android SDK 36，在不纳入版本控制的 `local.properties` 中配置 SDK 路径。本机 JDK、SDK 路径沿用本地配置。

以下命令从仓库根目录执行；在 Unix 系统上，将 `.\gradlew.bat` 替换为 `./gradlew`。App 代码修改后的正式交付须遵循下文“固定交付流程”。

| 命令 | 用途 |
| --- | --- |
| `.\gradlew.bat :app:assembleAppDebug` | 构建调试 APK |
| `.\gradlew.bat :app:installAppDebug` | 安装到已连接的设备或模拟器；安装后通过 Android Studio 或设备启动 |
| `.\gradlew.bat :app:testAppDebugUnitTest` | 运行 JVM 单元测试 |
| `.\gradlew.bat :app:connectedAppDebugAndroidTest` | 运行设备测试 |
| `.\gradlew.bat :app:lintAppDebug` | 运行 Android Lint 静态检查 |

### 网页端

使用 Node.js 20 及以上版本、pnpm 9 及以上版本，在 `modules/web/` 目录中执行：

| 命令 | 用途 |
| --- | --- |
| `pnpm install` | 安装依赖 |
| `pnpm dev` | 启动开发服务，端口为 8080，需要 App 后端配合 |
| `pnpm type-check` | 检查类型 |
| `pnpm build` | 检查类型并生成生产构建产物 |
| `pnpm lint:fix` | 执行代码检查并自动修复 |
| `pnpm format` | 格式化代码 |

网页端构建产物自动同步到 Android 应用的步骤仅在 GitHub Actions 中执行。

## 编码与命名规范

- Kotlin 遵循官方代码风格，使用四个空格缩进，并保持与周边代码的包结构和架构模式一致。
- 类名使用大驼峰命名法（`PascalCase`），函数和属性使用小驼峰命名法（`camelCase`），Android 资源名使用下划线命名法（`snake_case`）。
- 网页端使用两个空格缩进，遵循仓库中的 ESLint 和 Prettier 配置，使用单引号，不加分号。

## 测试与验证

- JVM 单元测试使用 JUnit 4，设备测试使用 AndroidX JUnit 和 Espresso。
- 测试类使用 `FeatureTest` 形式命名，测试方法名描述具体行为；Kotlin 支持使用反引号命名测试方法。
- 逻辑修改应添加有针对性的回归测试；数据库结构修改应添加 Room 迁移测试。
- 当前未设置数值化的测试覆盖率门槛。
- 网页端修改需完成类型检查、构建和浏览器验证。

## 提交与合并请求

- 提交说明使用简洁中文，描述具体改动；可使用 `docs:` 等约定式提交前缀，仓库已配置 Commitizen。
- 保持提交聚焦，只提交本次任务涉及的文件。
- 合并请求应说明行为变化、关联相关问题，并列出验证结果；界面修改应附截图。

## 安全与本地配置

- 不提交凭据、签名配置、SDK 路径和构建缓存。
- 仓库镜像配置的修改仅保留在本地，具体说明见 `settings.gradle`。

## 固定交付流程

本流程仅适用于阅读 App 本身的代码修改。外部书源维护及本地规则调整的例外见“项目范围与书源边界”。

每次 App 代码修改完成后，依次执行 **提交 → 构建调试包（Debug）→ 安装到已启动的 MuMu 模拟器**。

1. 检查改动并完成相关验证；只提交本次任务涉及的文件，使用简洁中文提交说明。
2. 使用 JDK 17、Android SDK 36，从仓库根目录运行：

   ```powershell
   $env:GRADLE_USER_HOME = (Get-Location).Path
   .\gradlew.bat :app:assembleAppDebug --offline --console=plain
   ```

   本机 JDK、SDK 路径不写入版本控制；离线构建要求依赖已缓存。
3. 通过 MuMuManager 的 `info -v all` 查询已启动实例及其 ADB 地址，连接对应设备。
4. 从 `app/build/outputs/apk/app/debug/output-metadata.json` 获取本次 APK 文件名，执行：

   ```powershell
   adb -s <设备地址> install -r <APK路径>
   ```

   覆盖安装并保留应用数据，核验安装成功及 `io.legado.app.debug` 的版本信息。
5. 汇报提交编号、构建和安装结果；失败时明确报告原因，不跳过失败步骤或声称成功。

## 历史改动与验证记录

以下为 2026-10-14 的改动记录，验证结论仅对应当时的改动。

### AI 对话附件上传

- 发送按钮旁新增“＋”上传入口，提供拍照、相册、文件三个选项。
- 复用现有附件发送逻辑；新增待发送预览、移除、仅附件发送和聊天记录附件展示。
- 处理相机权限、取消选择、临时照片清理和附件读取大小限制。
- 支持 PNG、JPEG、GIF、WebP 图片及 UTF-8 文本文件；最多 4 个附件，图片上限 5 MB、文本上限 256 KB、总大小上限 10 MB。
- 附件、对话编解码、输入栏避让共 11 项单元测试通过；调试包构建及 MuMu 主实例覆盖安装成功。
- 未做拍照、相册和文件选择的模拟器交互验证。

### AI 流式回复滚动优化

- 原因：文本分片触发整条回复重绑及变化动画，主线程解析 Markdown，执行步骤更新触发整列表刷新。
- 修复：消息差异更新及正文、步骤局部刷新，合并 50 毫秒内的文本更新，后台串行解析 Markdown，逐帧跟随回复高度。
- 向上翻看历史时停止自动跟随，回到底部或发送新消息后恢复；销毁视图时释放滚动监听和渲染任务。
- 验证：5 项 JVM 回归测试及 1 项 MuMu 合成流式设备测试通过，覆盖步骤视图保留、历史位置稳定和最终文字完整显示；设备测试通过 ADB 直接运行 AndroidJUnitRunner。

### 预设提示词置前

- 系统提示词顺序改为“预设 → 人格 → 工具说明”，同步更新中英文预设使用说明。
- 保留未启用预设时的原逻辑；预设切换、编辑和停用不会累积旧内容。
- 验证：3 项系统提示词回归测试通过。

### 网页端 AI 文件上传入口

- 将附件图标改为发送按钮旁的“上传文件”按钮，补充支持格式和选择、拖拽、粘贴说明。
- 复用已有多附件预览、移除、仅附件发送及后端校验；支持图片与 UTF-8 文本，最多 4 个、总大小 10 MB。
- 前端类型检查、页面 ESLint 和生产构建通过；将构建产物同步到 APK 的网页端资源目录。

### 网页端 AI 输入框布局调整

- 移除输入框底部的附件格式提示，保留上传按钮的悬浮说明。
- 将纵向拖动调整大小的原生手柄移到输入框外框右下角，正文随外框高度伸缩。
- 前端类型检查、页面 ESLint 和生产构建通过，并同步 APK 内的网页端资源。
