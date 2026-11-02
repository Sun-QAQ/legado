# 离线语音助手资源

随 APK 提供唤醒和中文流式识别模型，运行时不下载模型、不保存或上传录音。仅开启后加载，后台释放。

- 引擎：sherpa-onnx v2.0.1 官方 static-link-onnxruntime AAR（CPU，Android API 21+）。
- 引擎下载：https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar
- 中文识别：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
- 唤醒模型：https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
- 使用归档中的 int8 模型；KWS 选 epoch-12-avg-2，ASR 选 epoch-99-avg-1。仅复制 encoder/decoder/joiner 和 tokens，不包含上游测试音频。
- 关键词：你好小悦，拼音 token 为 `n ǐ h ǎo x iǎo y uè`。阈值需通过真机真人语音继续评估。
- 语音活动检测：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx；Silero VAD 原项目 MIT 许可见 LICENSE-silero-vad.txt。
- 引擎 Apache-2.0 许可见 LICENSE-sherpa-onnx.txt；上游项目：https://github.com/k2-fsa/sherpa-onnx
- res/raw/voice_*.wav 及 androidTest/assets/voice/*.wav 为本机离线中文语音合成生成的提示和测试素材，16 kHz、16 bit、单声道；测试素材不进入正式 APK。

## 使用与行为

设置 → 语音助手设置 → 语音助手。固定唤醒词“你好小悦”，播报“我在听”后说请求，也可点击“立即说话”。
关闭开关、离开前台或锁屏时停止录音；Activity 切换时立即停止旧录音，并在新页面恢复。
支持书架书名、当前分组序号、两种书架布局的可见分组、书架/发现、上下页、眼动翻页。
同名或同音书籍要求语音序号或点击确认；同音建议仅 Android 10+ 可用。
识别使用 modified_beam_search，以固定命令及最多200条书名/分组名做本地热词增强。热词仅在内存中使用，不写入文件、不上传。
只将 VAD 检测到的语音（含500毫秒前置缓存）送入识别器，6秒无语音退出，单次最长15秒。
语音反馈优先使用设备离线中文 TTS，无可用语音时播放内置简短反馈并显示完整文字。
语音助手开启后默认使用联网 AI 理解复杂指令，基础指令优先离线匹配。旧版独立 AI 开关的历史值不再影响请求路由。“语音助手 AI 模型”可跟随当前供应商，或单独选择已配置供应商和模型。
联网理解发送识别文字、当前页面/书籍、最多100本书的书名/作者/阅读时间、已知分组归属和最多50个分组名；不发送音频、正文、书源网址或书籍网址。
可理解作者、日期、最近阅读和“这本书”等上下文，支持最多4步操作。先校验完整方案，再逐步执行；歧义通过语音序号或点击选择，保留候选后的剩余步骤。失败、权限/校准待处理时停止后续操作。
网络请求最长25秒，响应体最大64 KiB；取消、关闭语音助手、切换模型或后台超出页面交接窗口会取消请求。等待期间页面/当前书籍/分组变化后不执行旧方案。
联网规划仅允许当前请求提供的临时书籍/分组ID，不接受任意URL、脚本、删除操作或模型自报的完成结果。
真实供应商设备测试需显式传入 liveVoiceAi=true，只发送合成书架上下文，不发送用户书架。
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
- `app/src/main/assets/voice_assistant/silero_vad.onnx`: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- `app/src/main/assets/voice_assistant/LICENSE-silero-vad.txt`: `2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b`
