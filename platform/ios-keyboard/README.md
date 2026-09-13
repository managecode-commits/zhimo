# iOS/iPadOS Keyboard Extension

本目录包含 Custom Keyboard `UIInputViewController`，通过共享 Swift/C ABI 会话处理字母、候选、空格和退格，并保留系统“下一个键盘”键。

iOS 第三方键盘扩展不能访问麦克风，因此此 target 不请求录音权限；语音转录应放在容器 App 中，或使用系统提供的听写入口。Xcode 工程、App Group、签名、容器 App 与真机商店审核仍需 Apple 开发环境。

当前目录没有独立 `.xcodeproj`。核心 XCFramework、Xcode target 接入与命令行构建步骤参见
[全平台构建手册](../../docs/构建与验收.md#62-ios-容器-app-与-keyboard-extension)。
