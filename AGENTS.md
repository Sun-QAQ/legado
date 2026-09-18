# AGENTS.md

## 提交规则
- 每次代码修改完成后必须执行 git add+commit 提交；提交信息用中文（仓库惯例）。
- **每次改动完成后的标准流程：提交 → 构建 → 安装到模拟器**（构建通过后再 `adb install` 部署到 MuMu 模拟器，见下文 adb 连接/安装）。

## 构建（Windows PowerShell）
```powershell
$env:JAVA_HOME="G:\Software\Java\java17"; $env:GRADLE_USER_HOME="S:\Projects\Android\legado"; .\gradlew.bat :app:assembleAppDebug --console=plain > 日志路径 2>&1; Get-Content 日志路径 | Select-String "BUILD|error|FAILED|e: file"
```
要点：
- 必须 JDK17（本机 `JAVA_HOME` 指向 Java8，需显式覆盖；AGP 8.13.2 需 JDK 11+，room 插件需 JDK 17）；Android Studio 自带 JBR 缺 `lib\jvm.cfg` 不可用。
- 必须 `GRADLE_USER_HOME` 指向项目目录（依赖缓存在此），否则 Gradle 去 `C:\Users\27497\.gradle` 找缓存会挂起/超时。
- 任务名带 flavor：`processAppDebugResources` / `compileAppDebugKotlin` / `assembleAppDebug`；`compileDebugKotlin` 是模糊匹配会失败。
- 输出重定向到日志文件再检索，勿直接管道给 `Select-String`（子进程被 kill）。
- 本机依赖缓存完整，`--offline` 可用（已多次验证）；新增/更换依赖时不要加。

## adb 连接/安装（MuMu 12 模拟器）
- 模拟器：MuMu 12 装在 `G:\Software\MuMuPlayer-12.0`，当前实例 `MuMuPlayer-12.0-0`（Redmi K70 Pro 机型，Android 12，屏幕 1272x2450）。
- 标准 adb 路径：`G:\Software\AndroidStudioSDK\platform-tools\adb.exe`（不在 PATH）。
- 连接步骤：
  1. 先确认模拟器已启动（任务管理器有 `MuMuNxDevice/MuMuNxMain` 进程，或 VBox 日志 `bootup finished`）。
  2. 用 MuMu 自带 adb `G:\Software\MuMuPlayer-12.0\nx_device\12.0\shell\adb.exe` 跑 `adb devices`（其会注册 guest IP 端点），或直接：
  3. `& "G:\Software\AndroidStudioSDK\platform-tools\adb.exe" connect 192.168.5.45:5555` → 应显示 `device`（非 offline）。
- 安装（构建成功后）：
  ```powershell
  $adb="G:\Software\AndroidStudioSDK\platform-tools\adb.exe"
  $apk=(Get-ChildItem "S:\Projects\Android\legado\app\build\outputs\apk\app\debug\legado_app_*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
  & $adb -s 192.168.5.45:5555 install -r $apk
  ```
  （apk 输出路径是 `app\build\outputs\apk\app\debug\legado_app_*.apk`，文件名带版本号；不是 `appDebug\app-debug.apk`。）
- 若 MuMu 实例换成 local_connect 模式或换实例，端口会变：查 `G:\Software\MuMuPlayer-12.0\vms\<实例名>\configs\vm_config.json` 里 `port_forward.adb.host_port`。

## 结构
- 模块：`:app`（Android App，namespace `io.legado.app`）、`:modules:book`、`:modules:rhino`。minSdk 21 / targetSdk 36 / Java 17。
- UI 基类在 `app/src/main/java/io/legado/app/base/`（`BaseActivity`/`BaseFragment`/`BaseDialogFragment`），viewBinding；`attachBaseContext` 统一走 `AppContextWrapper.wrap`（语言/字号配置）。
- AI Agent：系统提示词在 `ui/book/agent/AgentViewModel.kt` 的 `SYSTEM_PROMPT`；书源创建用独立的 `SOURCE_CREATE_PROMPT`。工具注册表 `AgentTools.kt`（`AgentTools.overview()` 动态生成注入提示词，新增工具无需改 prompt）；`AgentTool.name`/`description` 同时驱动工具 schema 与提示词摘要，修改后两处同步生效。
- 设计原型：仓库根 `ui-redesign-preview.html`（改 UI 前先参考）。
- `modules/web/` 是**独立的 Vue3+Vite+Element Plus 网页端子项目（不属于 Gradle 构建，settings.gradle 未 include）**。构建需 node ≥20 / pnpm ≥9，`pnpm build` = type-check + build + sync。产物已提交在 `app/src/main/assets/web/vue/`；`scripts/sync.js` 只在 GitHub workflow 环境（`GITHUB_ENV`）自动拷贝 dist，本地改 web 后需手动把 `modules/web/dist` 拷到 `app/src/main/assets/web/vue` 再提交。
- Cronet：jar 已提交在 `app/cronetlib/`。升 Cronet 需改 `gradle.properties` 的 `CronetVersion` 后跑 `gradlew app:downloadCronet`（下载新 jar/so 并重写 `assets/cronet.json`）。

## 主题系统（易踩坑）
- 运行时主题设置色：`ThemeStore.primaryColor()`（主色）/ `accentColor()`（强调色）/ `bottomBackground()`（底栏背景色）。**XML/资源无法引用这些运行时值**，只能在代码里设（`backgroundTintList`/`setTextColor`/`setSelectedTabIndicatorColor` 等）。
- **不要**为跟随主题色而子类化 `Resources` 拦截 `getColor`——实测启动闪退，已回退。主题属性 `colorPrimary/colorAccent/colorPrimaryDark` 静态，公共 API 无法在运行时覆盖。
- `@color/primary`/`@color/accent`/`@color/primaryDark` 是直接色值（静态回退 + `ThemeStore` 默认值来源，未设置时 resolve 这些 attr）。`md_theme_*_primary` token 已删除，勿引用。
- 默认封面：`CoverImageView.load()` 用 `imageTintList = ThemeStore.primaryColor()` 上色；书名/作者文字=强调色（`context.accentColor`）。
- 需"跟随主题设置"的 M3 控件须逐处运行时着色：TabLayout（`setTabTextColors` + `setSelectedTabIndicatorColor`，无 `setSelectedTabTextColor` API）、FAB（`backgroundTintList`）、Switch（`applyTint`）等。改动前 grep `ThemeStore.accentColor` / `primaryColor` 看既有模式。
- 昼夜 token 在 `res/values/colors_tokens.xml` + `res/values-night/colors_tokens.xml`（`md_theme_light_*`/`md_theme_dark_*`）；布局用别名如 `@color/background_card`、`@color/color_surface_variant`。

## 资源/代码细节
- 同一 drawable 目录下不能同时存在同名 `.jpg` 与 `.xml`（重复资源编译错）。
- `android.content.res.Theme` 是 `Resources` 的嵌套类，import 用 `android.content.res.Resources.Theme`。