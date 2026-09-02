# Windows TSF

`CoreSession` 是 TSF COM DLL 使用的 RAII 核心边界，包含 UTF-8 输入、结构化动作、候选选择、隐私 scope、引擎切换和显式落盘。`probe.cpp` 已纳入 Linux CI 边界测试。

工程包含 `ITfTextInputProcessorEx`、按键 sink、同步 edit session、TSF composition、候选窗口、密码框透传、用户级 COM/TSF Profile 注册和卸载入口。Windows CI 会编译 DLL 与边界探针；可用 Visual Studio 构建：

```powershell
cmake -S platform/windows-tsf -B build/windows-tsf -A x64
cmake --build build/windows-tsf --config Debug
```

管理员或当前用户部署工具在安装目录中调用：

```powershell
regsvr32 build/windows-tsf/Debug/ShurufaTsf.dll
# 卸载：regsvr32 /u build/windows-tsf/Debug/ShurufaTsf.dll
```

第三方 IME 的正式安装包和 DLL 必须进行 Authenticode 签名。当前候选窗是非激活 Win32
基础实现；进入正式发布前还需在 Windows SDK 真机上完成 UILess/search candidate、每显示器
DPI、辅助功能、AppContainer 与 x64/ARM64 应用矩阵验收。
