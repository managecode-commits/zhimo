<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Windows 候选样式与 EmEditor 兼容

参考用户提供的 `屏幕截图 2026-09-14 030632.png` 实现原生 Win32 候选窗口，不复制截图中的第三方标识。

## 候选栏

- 白底细边框、两行布局；第一行拼音和“知墨”，第二行横排编号候选。
- 首项红色，其余蓝色；字体按宿主 DPI 缩放，长候选省略显示。
- 支持鼠标点击候选及左右翻页，不激活窗口，不使用剪贴板或模拟按键上屏。
- 通过当前 composition 的上下文申请编辑锁，使用与键盘相同的候选/分页路径。
- 优先 TSF 文本位置；布局不可用时尝试同一宿主的系统 caret；都不可用则隐藏，避免显示在错误窗口。
- 尽量避开显示器工作区边缘；这不是已通过实际多屏 DPI 验收的声明。
- 暂未实现 UI-less / UI Automation 候选接口，翻页箭头尚未根据页边界展示禁用状态。

## EmEditor 输入范围回退

此前未提供有效 TSF InputScope 的控件，只有标准 Edit/RichEdit 被允许输入，其他控件直接透传。
这是一条可能造成 EmEditor 文档区无法输入拼音的路径，但本机不能据此确认用户的实际失败点。

[EmEditor 官方 FindWindow 文档](https://help.emeditor.com/en/macro/window/find_window.html)
给出了 `EmEditorView` 窗口类。基于此增加窄范围回退，同时满足以下条件才允许组合：

1. 当前进程文件名为 EmEditor.exe；
2. 当前焦点窗口类为 EmEditorView，且属于当前进程；
3. 焦点窗口就是 TSF 上下文窗口，或是它的子窗口；
4. 没有显式密码/PIN 输入范围，也未检测到密码样式。

回退使用禁学习模式；不会将所有未知控件直接判为普通文本框，也不按进程名放行 EmEditor 内的全部对话框。
官方窗口类示例不证明每个 EmEditor 版本的焦点/TSF 窗口结构完全相同。

若仍失败，使用包内 `diagnose-emeditor.cmd`，8 秒内切回 EmEditor 文档区。
它只显示进程名称、当前诊断进程位数和窗口类，不读取文档内容或窗口标题。
请另附 EmEditor 版本、x64/x86，以及是否出现组合文字；当前包仅有 x64 DLL。
另可检查 EmEditor 的 [Toggle IME 命令](https://help.emeditor.com/en/cmd/edit/toggle_ime.html)
是否关闭了输入法。该命令是应用自身控制项，不应归因于候选窗口样式。

## 验证与测试包

- Windows Release DLL 与探针交叉编译通过，GDI 依赖已加入构建。
- 增加 EmEditor 回退条件的 7 项编译期断言，包括大小写匹配和进程/窗口/密码拒绝条件。
- 通用 `tools/verify.sh` 通过，包括默认拼音逐键输入、空格提交、中英切换、候选 ID 选择。
- 未在 Windows、真实 EmEditor、不同 DPI 中实际运行本轮 UI 或安装脚本；不宣称已复现并解决用户机器全部问题。

本机包：`target/packages/zhimo-windows-x64-emeditor-ui-20260914.zip`；相邻 `.zip.sha256` 提供校验值。
安装前切回微软拼音，运行旧版卸载，重启后移走旧 `C:\Program Files\Zhimo\Test-x64`，
再以同一账户管理员安装新包。保留 `%LOCALAPPDATA%\Zhimo\user`。

日志：`target/zhimo-emeditor-build.log`、`target/zhimo-emeditor-regression.log`、
`target/zhimo-emeditor-package.log`。本轮未提交或推送。
