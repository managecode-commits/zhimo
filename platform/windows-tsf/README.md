# Windows TSF

`CoreSession` 是 TSF COM DLL 使用的 RAII 核心边界，包含 UTF-8 输入、候选选择、隐私 scope、引擎切换和显式落盘。`probe.cpp` 已纳入 Linux CI 边界测试。

Windows 产品壳仍需在 Windows SDK 下实现 `ITfTextInputProcessorEx`、按键 sink、edit session、composition、候选窗口、COM 注册和签名安装包。可用 Visual Studio 构建边界探针：

```powershell
cmake -S platform/windows-tsf -B build/windows-tsf -A x64
cmake --build build/windows-tsf --config Debug
```
