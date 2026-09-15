<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# macOS InputMethodKit

## 2026-09-15：新增完整脚本构建入口（未在 macOS 验证）

本目录已补入 Info.plist、麦克风权限声明、原生候选窗、默认拼音、数字选词、PageUp/PageDown 翻页、Shift+Space 中英文切换、输入法菜单、非抢焦点单字手写与离线语音面板。菜单可切换语言、打开面板和控制本地词频学习。默认关闭词频学习，以保守处理 IMK 客户端私密字段元数据不足的情况；开启后不能宣称能识别所有应用的隐私字段。

手写共用 Rust 单字模型，不含 APK 的图像融合与连写；语音为 AVAudioEngine 默认麦克风采集、CPU 离线转录、只读预览并确认上屏。声音不写入录音文件。面板失焦关闭、使用会话代次拒绝过期结果；安全输入模式下不打开面板。未申请辅助功能权限，不读取周边文档。

在 **macOS + Xcode 命令行工具 + Rust + CMake** 中运行：

```bash
bash tools/prepare-offline-speech.sh
bash tools/build-macos.sh
```

脚本通过 `swiftc` 直接构建完整 `.app`，无需手工创建 `.xcodeproj`；支持 arm64 + x86_64 Universal，最低部署目标暂设 macOS 12（仍需对应系统验收）。构建同主机架构的 Swift/C ABI 探针并执行，打包模型、图标、语音 dylib、许可证和 `install.command`。默认 ad-hoc 签名只用于本地开发测试，不能视作正式可信发行。

公开分发可显式指定：

```bash
ZHIMO_MAC_SIGN_IDENTITY='Developer ID Application: YOUR NAME (TEAMID)' \
ZHIMO_MAC_NOTARY_PROFILE='已有的钥匙串公证配置名称' \
bash tools/build-macos.sh
```

不把私钥或账号密码写入脚本。只有设置公证配置时才调用 Apple 服务；公证成功后输出另一个 `-notarized.zip`。GitHub 的 `macOS input method (manual validation build)` 工作流提供无证书测试构建入口，当前仅新增工作流文件，未触发远程运行。

解压产物后运行 `bash install.command`，安装到当前用户的 `~/Library/Input Methods/Zhimo.app`。旧版移至 `~/Library/Application Support/Zhimo/InstallBackups` 保留。保存文档并注销登录后，在系统输入源中手动添加知墨；不强制修改默认输入源、不结束编辑器进程。

## 当前验证边界

Linux 已通过 plist／资源／构建契约检查和 Bash 语法检查。**当前环境没有 Swift、macOS SDK、Mac 宿主或 Developer ID，不能进行 Swift 类型检查、IMK 启动、系统安装和公证；没有生成可用 macOS 安装包。** 不应把新增源码称为 Mac 实机已完成。

需在 Mac 上验证：TextEdit／Safari／Chrome／VS Code／终端；全拼／简拼／选词；密码和安全输入；同进程多窗口切换；手写拖动／点选；麦克风拒绝／录音取消／转录上屏；Intel 与 Apple Silicon；签名与 Gatekeeper。

## 原有手动集成说明（替代路径）

应用图标已提供在 `Assets.xcassets/AppIcon.appiconset`。创建 target 后加入该资源目录，
设置 App Icon Source 为 `AppIcon`；当前仅交付资源，未进行 macOS 图标编译与安装验证。

`InputController.swift` 是 InputMethodKit 原生控制器，已连接统一 C ABI，并正确使用 UTF-16 光标长度。将本目录源码、`platform/apple/ZhimoSession.swift`、`ZhimoCore` module map 与 macOS 静态库加入 Input Method App target。

Info.plist 与候选窗已补入源码；在 Xcode 手动创建 target 时亦可使用。签名及安装到 `~/Library/Input Methods` 后的真实应用兼容测试仍须在 macOS 完成。

当前目录没有独立 `.xcodeproj`，优先使用上述脚本。Universal Rust 静态库、InputMethodKit target 手动创建、安装和
启用步骤参见 [全平台构建手册](../../docs/构建与验收.md#63-macos-inputmethodkit)。
