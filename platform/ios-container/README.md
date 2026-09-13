# iOS 容器 App 语音转录

第三方键盘扩展没有麦克风权限，因此容器 App 使用 Apple Speech/AVAudioEngine 提供合规的实时听写与文本复制/分享入口。若设备支持端侧识别，源码强制 `requiresOnDeviceRecognition`；否则产品应明确提示网络能力差异。

正式 target 需添加 `NSSpeechRecognitionUsageDescription`、`NSMicrophoneUsageDescription`，并在真机验证权限拒绝、来电中断、后台切换和长录音恢复。

当前目录没有独立 `.xcodeproj`；需与容器 App、键盘扩展和
`target/apple-xcframework/ShurufaCore.xcframework` 组合为 Xcode 工程。完整创建 target、
构建和签名步骤参见 [全平台构建手册](../../docs/构建与验收.md#62-ios-容器-app-与-keyboard-extension)。
