<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Windows 测试安装包

## 2026-09-15：x64 + x86 双架构修复包（优先使用）

针对 EmEditor 7.00.3 的 **32 位进程**补齐 x86 TSF DLL 和核心。新包：`target/packages/zhimo-windows-x64-x86-20260915.zip`。安装与回滚说明见 [双架构安装说明](../platform/windows-tsf/DUAL-INSTALL.md)。以下历史 x64 包不能修复 32 位应用缺少组件的问题。

在管理员 64 位 PowerShell 中运行 `.\install.cmd -Upgrade`，完成后注销并重新登录。保留旧版本目录和用户词库。

新增 `STDAPICALLTYPE` 和无修饰 COM 导出名；两套位数的安装前探针检查 DLL 加载、COM 类工厂调用和拼音候选上屏。32 位 TSF 通过独立进程复用 x64 手写/语音面板，不混装不同位数的 DLL。本机完成交叉编译、PE 架构、导出和依赖检查，未在真实 Windows/EmEditor 上运行。

复现构建：安装 Rust 的 `i686-pc-windows-gnu` 和 `x86_64-pc-windows-gnu` target，准备两种 MinGW POSIX 编译器、已有离线模型和 shared-speech 依赖，设置 `MINGW_X86_ROOT`、`MINGW_X64_ROOT` 后运行 `bash tools/build-windows-dual.sh`。可通过 `CMAKE` 指定 CMake 可执行文件，`CARGO_NET_OFFLINE=true` 使用已有 Cargo 缓存。

## 历史版本

最新 EmEditor 兼容及截图样式测试版见 [Windows 候选样式与 EmEditor 兼容](Windows候选样式与EmEditor兼容.md)。

## 拼音初始化修复版（优先使用）

- 本机包：`target/packages/zhimo-windows-x64-pinyin-fix-20260913.zip`
- 大小：11,250,409 字节。
- SHA-256：`75d44924325df1284d790496ce08d941b9f03924f5cb9a4bf2204d8de7edeee8`

旧包存在确定的初始化错误：TSF 的 `pinyin_` 初值为 true，但创建的 bilingual Runtime
默认激活 latin。之前探针先显式切换拼音，掩盖了真实首键路径的问题。
现由 Windows CoreSession 在构造时激活拼音；切换失败则创建无效会话，避免状态不一致。
没有修改其他端的 bilingual 默认值，也没有取消密码/未知输入范围的保护。

已先用去掉主动切换的逐键探针复现失败，再验证修复后通过。探针现在检查：
默认逐键 `n/i/h/a/o` 候选、空格提交“你好”、切中英再切回、候选 ID 提交。
Windows Release DLL/探针重新交叉编译并通过导出和 DLL 依赖闭包检查。
这仍不是 Windows 实机 TSF 验收；未提供可靠输入范围的宿主仍可能透传。

升级步骤：切回微软拼音 → 运行旧包 `uninstall.cmd` → 重启 → 移走/删除旧的
`C:\Program Files\Zhimo\Test-x64` → 完整解压新包并以同一账户管理员运行 `install.cmd`
→ 注销重登测试。保留 `%LOCALAPPDATA%\Zhimo\user`。

修复记录：`target/zhimo-windows-pinyin-fix-build.log`、
`target/zhimo-windows-pinyin-fix-package.log`、`target/zhimo-windows-pinyin-fix-regression.log`。

## 历史首个测试包（不要继续安装）

本轮根据当前工作区重新编译 Rust Runtime 和 TSF 的 Release 版本，未提交或推送代码。

- 本机包：`target/packages/zhimo-windows-x64-test-20260913.zip`
- 大小：11,249,195 字节（约 10.7 MiB）
- SHA-256：`f602feca17be26030820c15350891977973cd82b9fd10ae70dc68346a99a5c8a`
- 相邻 `.zip.sha256` 提供校验值；解压目录含逐文件 `SHA256SUMS.json`。
- x64 Release；未签名、未在真实 Windows 宿主验收。不是 ARM64 或 32 位输入法包。

完整解压后，在将使用输入法的同一 Windows 管理员账户中右键 `install.cmd`，选择
“以管理员身份运行”。注销重登后使用 Win+空格切换；缺少键盘时在简体中文语言选项中添加。
安装脚本将文件复制到 Program Files 下的 `Zhimo\Test-x64`，不会替换已有安装。
卸载请先切回微软拼音，再以同一账户管理员运行 `uninstall.cmd`。

完整安装、执行策略、手动安装、卸载和故障收集步骤见
[Windows 测试安装说明](../platform/windows-tsf/TEST-INSTALL.md)。

## 验证与限制

已经完成 Release 构建、PE x64 架构检查、4 个 COM 导出检查、全部 DLL 导入依赖闭包检查、
ZIP CRC 与逐文件 SHA-256 校验。包内附带 `libgcc_s_seh-1.dll`、`libstdc++-6.dll`、
`libwinpthread-1.dll` 和对应许可信息，不需要用户从第三方 DLL 网站补文件。

Windows 原生安装脚本、注册、探针执行、真实输入和卸载尚未在 Windows 运行。
安装时脚本先在目标 Windows 执行探针，失败则不会继续注册。
当前参考拼音词库与 Android 完整 Rime 能力不一致，也没有桌面手写/语音 UI；建议使用测试机。

## 复现打包

先使用对应 MinGW 工具链构建 `ime-ffi --release --target x86_64-pc-windows-gnu`，
CMake 设置 `CMAKE_SYSTEM_NAME=Windows`、对应 C++ 编译器、`CMAKE_BUILD_TYPE=Release`，
并通过 `ZHIMO_IME_LIBRARY`、`ZHIMO_IME_RUNTIME_DLL` 指向该 Release Rust 产物。

```bash
python3 tools/package-windows-test.py \
  --build target/windows-tsf-release \
  --runtime target/x86_64-pc-windows-gnu/release/ime_ffi.dll \
  --mingw-root /absolute/path/to/mingw-root \
  --output target/packages/zhimo-windows-x64-test-new
```

输出路径和 ZIP 必须尚不存在。此脚本针对 Linux 主机上的 Debian 风格 MinGW SDK 布局，
不是 Windows/MSVC 打包器。包中的 `BUILD-INFO.json` 记录编译器、HEAD、脏工作区状态和导入依赖。

本机日志：`target/zhimo-windows-release-runtime.log`、`target/zhimo-windows-release-tsf.log`、
`target/zhimo-windows-package.log`。生成的包和日志位于忽略目录，不会自动上传 GitHub。
