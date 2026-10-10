# 眼动翻页模型

`face_landmarker.task` 是 Google MediaPipe Face Landmarker 官方 float16 v1 模型，
随 APK 打包，推理完全在设备本地执行。摄像头帧不保存、不上传。

- 模型地址：https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
- 官方说明：https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- SHA-256：`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`
- Android 依赖：`com.google.mediapipe:tasks-vision:0.10.32`

识别结果包含 478 个脸部关键点，包括双眼虹膜。使用虹膜相对眼睑及眼角的位置
估计垂直视线，上下两点校准后检测「底部停留 → 向上看」动作。
眨眼、多人、人脸丢失、明显头部位移及不连续采样会清除待触发手势。
这是一种基于前置摄像头的视线估计，效果受光线、眼镜反光、握持姿势影响。
需要 Android 7.0 及以上；仅用户主动启用后加载模型与打开摄像头。
