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

用户要求：以后每次修改完成后，依次执行 **提交 → 构建 Debug 包 → 安装到已启动的 MuMu 模拟器**。

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

## 2026-10-14：AI 对话附件上传

- 发送按钮旁新增“＋”上传入口，提供拍照、相册、文件三个选项。
- 复用现有附件发送逻辑；新增待发送预览、移除、仅附件发送和聊天记录附件展示。
- 处理相机权限、取消选择、临时照片清理和附件读取大小限制。
- 支持 PNG、JPEG、GIF、WebP 图片及 UTF-8 文本文件；最多 4 个附件，图片上限 5 MB、文本上限 256 KB、总大小上限 10 MB。
- 附件、对话编解码、输入栏避让共 11 项单元测试通过；Debug 构建及 MuMu 主实例覆盖安装成功。
- 未做拍照、相册和文件选择的模拟器交互验证。
