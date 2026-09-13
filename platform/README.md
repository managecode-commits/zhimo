# 平台适配层

该目录包含 Windows TSF 边界、Linux Fcitx 5/IBus、macOS InputMethodKit、Android IME 和 iOS Keyboard Extension 的平台工程或适配源码。

所有适配必须遵循 [Platform Bridge v1](../docs/api/platform-bridge.md)。公共 C ABI 位于
`crates/ime-ffi` 和 `include/shurufa_ime.h`。统一的环境准备、编译、安装、卸载和验收命令
参见 [全平台构建、安装与验收手册](../docs/构建与验收.md)，各平台目录 README 补充平台
特有约束。
