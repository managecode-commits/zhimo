# macOS InputMethodKit

`InputController.swift` 是 InputMethodKit 原生控制器，已连接统一 C ABI，并正确使用 UTF-16 光标长度。将本目录源码、`platform/apple/ShurufaSession.swift`、`ShurufaCore` module map 与 macOS 静态库加入 Input Method App target。

目标需在 Xcode/macOS 上补齐签名、`Info.plist`、候选窗、安装到 `~/Library/Input Methods` 后的真实应用兼容测试。本 Linux 环境不能编译或签名该目标。

当前目录没有独立 `.xcodeproj`。Universal Rust 静态库、InputMethodKit target 创建、安装和
启用步骤参见 [全平台构建手册](../../docs/构建与验收.md#63-macos-inputmethodkit)。
