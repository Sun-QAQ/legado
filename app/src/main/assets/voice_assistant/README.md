# 离线语音助手资源

随 APK 提供唤醒和中文流式识别模型，运行时不下载模型、不保存或上传录音。仅开启后加载，后台释放。

- 引擎：sherpa-onnx v2.0.1 官方 static-link-onnxruntime AAR（CPU，Android API 21+）。
- 引擎下载：https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar
- 中文识别：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
- 唤醒模型：https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
- 使用归档中的 int8 模型；KWS 选 epoch-12-avg-2，ASR 选 epoch-99-avg-1。仅复制 encoder/decoder/joiner 和 tokens，不包含上游测试音频。
- 关键词：你好小悦，拼音 token 为 `n ǐ h ǎo x iǎo y uè`。阈值需通过真机真人语音继续评估。
- 引擎 Apache-2.0 许可见 LICENSE-sherpa-onnx.txt；上游项目：https://github.com/k2-fsa/sherpa-onnx
- res/raw/voice_*.wav 及 androidTest/assets/voice/*.wav 为本机离线中文语音合成生成的提示和测试素材，16 kHz、16 bit、单声道；测试素材不进入正式 APK。

## 使用与行为

设置 → 其他设置 → 语音助手。固定唤醒词“你好小悦”，播报“我在听”后说请求，也可点击“立即说话”。
关闭开关、离开前台或锁屏时停止录音；Activity 切换时立即停止旧录音，并在新页面恢复。
支持书架书名、当前分组序号、两种书架布局的可见分组、书架/发现、上下页、眼动翻页。
同名或同音书籍要求语音序号或点击确认；同音建议仅 Android 10+ 可用。
识别使用 modified_beam_search，以固定命令及最多200条书名/分组名做本地热词增强。热词仅在内存中使用，不写入文件、不上传。
语音反馈优先使用设备离线中文 TTS，无可用语音时播放内置简短反馈并显示完整文字。
可选 AI 理解默认关闭。开启后仅将文字和受限书架摘要发送给已配置供应商，不发送音频。
VoiceRecognitionEngine 接口保留联网识别的替换点；当前只实现离线引擎。

## SHA-256

- `app/libs/sherpa-onnx-2.0.1.aar`: `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`
- `app/src/main/assets/voice_assistant/asr/decoder-epoch-99-avg-1.int8.onnx`: `22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf`
- `app/src/main/assets/voice_assistant/asr/encoder-epoch-99-avg-1.int8.onnx`: `1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1`
- `app/src/main/assets/voice_assistant/asr/joiner-epoch-99-avg-1.int8.onnx`: `a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169`
- `app/src/main/assets/voice_assistant/asr/tokens.txt`: `8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac`
- `app/src/main/assets/voice_assistant/kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`: `ed83454004d5bd16d831eaf00adcd181ed7734886aab6ef440f3ffa5aa3cfe3b`
- `app/src/main/assets/voice_assistant/kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`: `dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d`
- `app/src/main/assets/voice_assistant/kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx`: `f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24`
- `app/src/main/assets/voice_assistant/kws/keywords.txt`: `520a0549381c2ed24da434fe56a03314c9f14194c4198d57ed80797248b420f3`
- `app/src/main/assets/voice_assistant/kws/tokens.txt`: `72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e`
- `app/src/main/assets/voice_assistant/LICENSE-sherpa-onnx.txt`: `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`
- `app/src/main/assets/voice_assistant/asr/hotwords.txt`: `69cd28b5d646f688005a2de94eb95722ad85b3d72d6089aa80875e44d78e47c0`
