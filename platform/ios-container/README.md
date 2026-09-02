# iOS 容器 App 语音转录

第三方键盘扩展没有麦克风权限，因此容器 App 使用 Apple Speech/AVAudioEngine 提供合规的实时听写与文本复制/分享入口。若设备支持端侧识别，源码强制 `requiresOnDeviceRecognition`；否则产品应明确提示网络能力差异。

正式 target 需添加 `NSSpeechRecognitionUsageDescription`、`NSMicrophoneUsageDescription`，并在真机验证权限拒绝、来电中断、后台切换和长录音恢复。
