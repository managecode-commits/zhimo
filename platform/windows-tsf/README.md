<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Windows TSF

最新 [候选样式与 EmEditor 兼容记录](../../docs/Windows候选样式与EmEditor兼容.md)：
紧凑横排候选、点击选词/翻页、窄范围无学习兼容回退。仍需真实 Windows 验收。

Windows x64 ZIP 测试包的安装、卸载、故障收集及已知限制见 [测试安装说明](TEST-INSTALL.md)。
包内提供同一账户管理员运行的 `install.cmd` / `uninstall.cmd`；不是已签名的正式安装器。

最新安全边界与验证范围见 [跨平台底座整改交付](../../docs/跨平台底座整改交付.md)。
输入核心现在只在 TSF 授予编辑锁后修改；InputScope 密码/PIN 透传，Private 禁学习。
未提供可靠 scope 的自定义控件保守透传，可能降低兼容性，需要 Windows 宿主矩阵实测。
左右箭头操作组合光标，PageUp/PageDown 翻页；候选鼠标已实现，UI-less 仍待开发。

`CoreSession` 是 TSF COM DLL 使用的 RAII 核心边界，包含 UTF-8 输入、结构化动作、候选选择、隐私 scope、引擎切换和显式落盘。`probe.cpp` 已纳入 Linux CI 边界测试。

工程包含 `ITfTextInputProcessorEx`、按键 sink、同步 edit session、TSF composition、候选窗口、密码框透传、用户级 COM/TSF Profile 注册和卸载入口。Windows CI 会编译 DLL 与边界探针；可用 Visual Studio 构建：

```powershell
cmake -S platform/windows-tsf -B build/windows-tsf -A x64
cmake --build build/windows-tsf --config Debug
```

执行上述 CMake 命令前必须先运行 `cargo build -p ime-ffi`，生成 MSVC import library 和
Runtime DLL。Debug/Release 完整命令、探针、注册、刷新 `ctfmon` 和卸载顺序参见
[全平台构建手册](../../docs/构建与验收.md#5-windows-tsf-输入法)。

管理员或当前用户部署工具在安装目录中调用：

```powershell
regsvr32 build/windows-tsf/Debug/ZhimoTsf.dll
# 卸载：regsvr32 /u build/windows-tsf/Debug/ZhimoTsf.dll
```

第三方 IME 的正式安装包和 DLL 必须进行 Authenticode 签名。当前候选窗是非激活 Win32
基础实现；进入正式发布前还需在 Windows SDK 真机上完成 UILess/search candidate、每显示器
DPI、辅助功能、AppContainer 与 x64/ARM64 应用矩阵验收。
