# 平台适配层

该目录包含 Windows TSF 边界、Linux Fcitx 5/IBus、macOS InputMethodKit、Android IME 和 iOS Keyboard Extension 的平台工程或适配源码。

所有适配必须遵循 [Platform Bridge v1](../docs/api/platform-bridge.md)。公共 C ABI 位于 `crates/ime-ffi` 和 `include/shurufa_ime.h`；各目录 README 记录目标工具链、构建方法和仍需真机完成的验收项。
