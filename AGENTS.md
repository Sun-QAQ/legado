# AGENTS.md

## 工作流
- 代码修改完成后必须 `git add` 并提交，提交信息使用中文。
- 标准流程：提交 → `:app:assembleAppDebug` → 安装到 MuMu 模拟器。

## 构建与安装（Windows PowerShell）
```powershell
$env:JAVA_HOME="G:\Software\Java\java17"
$env:GRADLE_USER_HOME="S:\Projects\Android\legado"
.\gradlew.bat :app:assembleAppDebug --console=plain > build.log 2>&1
Get-Content build.log | Select-String "BUILD|error|FAILED|e: file"
```
- 必须使用 JDK17 和上述 `GRADLE_USER_HOME`。
- Gradle 任务必须带 flavor，如 `compileAppDebugKotlin`、`assembleAppDebug`。
- 输出先重定向到日志再检索；缓存完整时可用 `--offline`，变更依赖时不要用。
- adb：`G:\Software\AndroidStudioSDK\platform-tools\adb.exe`；APK：`app\build\outputs\apk\app\debug\legado_app_*.apk`。
- 优先使用 MuMu 管理器（`G:\Software\MuMuPlayer-12.0\nx_main\MuMuManager.exe`）的 `adb -v 0` 获取当前实例的 JSON 地址；从其中的 `adb_host` 和 `adb_port` 生成设备地址，再用标准 adb `connect`。不要硬编码旧 IP 或端口。内置 adb 的 `devices` 未发现设备时，管理器查询仍可获取已运行实例。
```powershell
$adb="G:\Software\AndroidStudioSDK\platform-tools\adb.exe"
$mumuInfo=& "G:\Software\MuMuPlayer-12.0\nx_main\MuMuManager.exe" adb -v 0 | ConvertFrom-Json
$device="{0}:{1}" -f $mumuInfo.adb_host,$mumuInfo.adb_port
& $adb connect $device
& $adb devices
$apk=(Get-ChildItem "app\build\outputs\apk\app\debug\legado_app_*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& $adb -s $device install -r $apk
```

## 项目结构
- `:app` 为 Android 应用（`io.legado.app`），另有 `:modules:book`、`:modules:rhino`；minSdk 21、targetSdk 36、Java 17。
- AI Agent 位于 `app/src/main/java/io/legado/app/ui/book/agent/`：提示词在 `AgentViewModel.kt`，工具注册在 `AgentTools.kt`；工具名称和描述同时用于 schema 与提示词摘要。
- `modules/web/` 是独立 Vue3/Vite 项目，不属于 Gradle；本地构建后需手动同步 `dist` 到 `app/src/main/assets/web/vue/`。
- UI 改动先参考根目录 `ui-redesign-preview.html`；Cronet 升级使用 `gradlew app:downloadCronet`。

## 关键约束
- 主题色来自 `ThemeStore`，需跟随主题的控件必须在代码中运行时着色；不要通过子类化 `Resources` 拦截颜色。
- 昼夜色值位于 `res/values/colors_tokens.xml` 与 `res/values-night/colors_tokens.xml`，布局使用颜色别名。
- 同一 drawable 目录不能存在同名 `.jpg` 与 `.xml`。
- `Theme` 应导入 `android.content.res.Resources.Theme`。
