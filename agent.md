# 开发记录与交付流程

## 固定流程

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
