# Repository Guidelines

## Project Structure & Module Organization

Legado is an Android reader with a Vue web interface. Android code lives in `app/src/main/java/io/legado/app/`: `ui/` contains screens and ViewModels, `data/` holds persistence, `model/` handles parsing, and `web/` implements the embedded server. Resources and bundled assets live in `app/src/main/res/` and `app/src/main/assets/`. JVM tests are in `app/src/test/`; device tests are in `app/src/androidTest/`; Room schemas are in `app/schemas/`.

`modules/book/` and `modules/rhino/` are Android libraries. `modules/web/src/` contains Vue/TypeScript code, with static assets in `modules/web/public/`. Dependency versions are centralized in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use JDK 17 and Android SDK 36. Configure the SDK location in untracked `local.properties`. Run these commands from the repository root; on Unix, replace `.\gradlew.bat` with `./gradlew`:

- `.\gradlew.bat :app:assembleAppDebug` — build the debug APK.
- `.\gradlew.bat :app:installAppDebug` — install on a connected device or emulator; launch through Android Studio or the device.
- `.\gradlew.bat :app:testAppDebugUnitTest` — run JVM tests.
- `.\gradlew.bat :app:connectedAppDebugAndroidTest` — run device tests.
- `.\gradlew.bat :app:lintAppDebug` — run Android Lint.

For web development, use Node.js 20+ and pnpm 9+. In `modules/web/`, run `pnpm install`, then `pnpm dev` (port 8080; requires the app's backend). `pnpm build` checks types and builds; asset synchronization into the Android app runs only in GitHub Actions. Use `pnpm type-check`, `pnpm lint:fix`, and `pnpm format` for validation and formatting.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for classes, camelCase for functions/properties, and snake_case for Android resource names. Match surrounding package and architectural patterns. Web files use two spaces, ESLint, and Prettier configured for single quotes and no semicolons.

## Testing Guidelines

Use JUnit 4 for JVM tests and AndroidX JUnit/Espresso for device tests. Name classes `FeatureTest` and describe behavior in test methods; Kotlin backtick names are supported. Add focused regression tests for logic changes and Room migration tests for schema changes. No numeric coverage threshold is configured. Verify web changes with type checking, a build, and browser checks.

## Commit & Pull Request Guidelines

History uses concise Chinese change summaries and occasional Conventional Commit prefixes such as `docs:`; Commitizen is configured. Keep commits focused and describe the concrete change. PRs should explain behavior, link relevant issues, list validation results, and include screenshots for UI changes.

## Security & Configuration Tips

Keep credentials, signing configuration, SDK paths, and build caches out of commits. Repository mirror changes are local-only, as noted in `settings.gradle`.

## 固定交付流程

本流程仅适用于阅读 App 本身的代码修改。外部书源的编写、调试、修改及本地规则维护不适用，不提交、不因此构建或安装 App。

用户要求：阅读 App 代码每次修改完成后，依次执行 **提交 → 构建 Debug 包 → 安装到已启动的 MuMu 模拟器**。

1. 检查改动并完成相关验证；只提交本次任务涉及的文件，使用简洁中文提交说明。
2. 使用 JDK 17、Android SDK 36，从仓库根目录运行：

   ```powershell
   $env:GRADLE_USER_HOME = (Get-Location).Path
   .\gradlew.bat :app:assembleAppDebug --offline --console=plain
   ```

   本机 JDK、SDK 路径沿用本地配置，不写入版本控制；离线构建要求依赖已缓存。
3. 通过 MuMuManager 的 `info -v all` 查询已启动实例及其 ADB 地址，连接对应设备。
4. 从 `app/build/outputs/apk/app/debug/output-metadata.json` 获取本次 APK 文件名，执行：

   ```powershell
   adb -s <设备地址> install -r <APK路径>
   ```

   覆盖安装并保留应用数据，核验安装成功及 `io.legado.app.debug` 的版本信息。
5. 汇报提交编号、构建和安装结果；失败时明确报告原因，不跳过失败步骤或声称成功。

## 外部书源与项目边界（2026-10-22）

- 本项目是阅读 App；实际书源全部由外部提供，不将具体书源、站点脚本、规则、调试材料或其说明作为 App 项目交付内容。
- 以后所有书源相关的编写、调试、修改均只保留在本地，不创建 Git 提交，也不使用 `git add -f` 绕过忽略规则。
- 本地书源材料统一放在已忽略的 `docs/book-sources/` 或 `book-sources/` 中；临时调试文件放在已忽略的 `.tmp/` 中。不得放入 App 资源、测试或其他受跟踪目录。
- App 对外部书源的通用导入、编辑、解析等功能属于 App 代码，可按正常流程维护；这不授权提交任何实际书源。
- 仅调整上述本地规则或 Git 忽略配置时，不触发提交、Debug 构建和模拟器安装。

## 2026-10-14：AI 对话附件上传

- 发送按钮旁新增“＋”上传入口，提供拍照、相册、文件三个选项。
- 复用现有附件发送逻辑；新增待发送预览、移除、仅附件发送和聊天记录附件展示。
- 处理相机权限、取消选择、临时照片清理和附件读取大小限制。
- 支持 PNG、JPEG、GIF、WebP 图片及 UTF-8 文本文件；最多 4 个附件，图片上限 5 MB、文本上限 256 KB、总大小上限 10 MB。
- 附件、对话编解码、输入栏避让共 11 项单元测试通过；Debug 构建及 MuMu 主实例覆盖安装成功。
- 未做拍照、相册和文件选择的模拟器交互验证。

## 2026-10-14：AI 流式回复滚动优化

- 原因：文本分片触发整条回复重绑及变化动画，主线程解析 Markdown，执行步骤更新触发整列表刷新。
- 修复：消息差异更新及正文/步骤局部刷新，合并 50 ms 内的文本更新，后台串行解析 Markdown，逐帧跟随回复高度。
- 向上翻看历史时停止自动跟随，回到底部或发送新消息后恢复；销毁视图时释放滚动监听和渲染任务。
- 验证：5 项 JVM 回归测试及 1 项 MuMu 合成流式设备测试通过，覆盖步骤视图保留、历史位置稳定和最终文字完整显示；设备测试通过 ADB 直接运行 AndroidJUnitRunner。

## 2026-10-14：预设提示词置前

- 系统提示词顺序改为“预设 → 人格 → 工具说明”，同步更新中英文预设使用说明。
- 保留未启用预设时的原逻辑；预设切换、编辑和停用不会累积旧内容。
- 验证：3 项系统提示词回归测试通过。

## 2026-10-14：Web AI 文件上传入口

- 将附件图标改为发送按钮旁的“上传文件”按钮，补充支持格式和选择、拖拽、粘贴说明。
- 复用已有多附件预览、移除、仅附件发送及后端校验；支持图片与 UTF-8 文本，最多 4 个、总大小 10 MB。
- 前端类型检查、页面 ESLint 和生产构建通过；将构建产物同步到 APK 的 Web 资源目录。

## 2026-10-14：Web AI 输入框布局调整

- 移除输入框底部的附件格式提示，保留上传按钮的悬浮说明。
- 将纵向拖动调整大小的原生手柄移到输入框外框右下角，正文随外框高度伸缩。
- 前端类型检查、页面 ESLint 和生产构建通过，并同步 APK 内的 Web 资源。
