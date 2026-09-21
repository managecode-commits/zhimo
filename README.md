<!-- Copyright © 2026 立方田 <managecode@gmail.com> -->
# Zhimo · 知墨输入法

![知墨 Zhimo](assets/branding/zhimo-128.png)

**拼音、手写、离线语音，让输入更贴近自己的表达。**

Zhimo 是一个开源、离线优先的输入法项目。你可以用拼音打字、手写选字，
也可以录音后在本机转成文字；开发者可以基于 Rust 核心扩展其他语种和平台。

**当前为开发预览版，不是稳定正式版。** Android、Windows、Ubuntu、Debian 和 macOS 提供测试包；
iOS 仍是原型。各平台功能与完成度不完全相同，macOS 尚未完成 IMK 实机兼容验收。

[下载安装包](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.1-preview.20260921.2) ·
[构建与安装手册](docs/构建与验收.md) ·
[图文介绍](docs/promotion/知墨输入法图文宣传稿.md) ·
[反馈问题](https://github.com/managecode-commits/zhimo/issues) ·
[参与贡献](CONTRIBUTING.md)

## 最新预览 · 2026-09-21

[`v0.1.1-preview.20260921.2`](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.1-preview.20260921.2)
提供新版 Android APK（版本码 5）；桌面安装包继续使用 `20260921.1`，本轮未重新编译桌面端。

- **快速输入**：后台串行处理查询、复用候选和预览控件，减少主线程开销；按键圆角缩小至 5dp。
- **九键连拼**：新增有界词典组句和后续候选翻页，回车/完成键选择汉字，不提交内部数字编码。
- **桌面大写**：Caps Lock 临时英文直输，关闭后恢复基础模式；Shift＋字母交给系统，状态显示同步更新。
- **离线语音**：Android 改为仅内置流式模型，移除 Whisper；按住空格录音、松开结束，最终结果直接上屏。APK 约 323 MiB。
- **发布范围**：本轮 Android 变更与限制见[发布说明](release/preview-20260921.2.md)；桌面构建和验证见[上一版说明](release/preview-20260921.1.md)。

## 主要功能

Android 流式语音的交互、构建和验收见[手机端语音说明](docs/Android流式语音与按住说话.md)。
桌面语音链路未改变。

- **拼音输入**：全拼、简拼、汉字候选与常用词条；Android 提供九键和 26 键，支持手动分词和九键拼音候选切换。
- **本地学习**：根据选词记录调整候选排序，帮助常用词和组合更容易被找到。具体行为取决于平台、引擎和学习设置。
- **离线手写**：书写、查看候选、点选上屏，支持撤笔和清空。桌面以单字为主，Android 的字形与连写识别仍在改进。
- **离线语音转文字**：Android 边录音边识别，临时结果仅在键盘预览，松手后最终文字直接上屏；桌面仍使用原有 Whisper 链路。速度与效果受设备、噪声及发音影响。
- **字符与常用表达**：英文、数字、标点输入；Android 提供符号与 Emoji 入口。

桌面输入示例：`nihao` / `nh` 可查找“你好”；输入 `jixu` 后，空格确认汉字候选，
回车直接上屏原始拼音 `jixu`。没有拼音组合时，回车交给当前应用正常处理。

### 界面预览

<img src="docs/promotion/images/zhimo-pinyin-26-development.png" alt="知墨 Android 26 键拼音与汉字候选" width="320">
<img src="docs/promotion/images/zhimo-pinyin-9-development.png" alt="知墨 Android 九键拼音候选切换" width="320">

以上为开发过程截图，不是本版最新实机截图；实际样式以安装版本为准。

## 下载与平台状态

Android 当前预览：[`v0.1.1-preview.20260921.2`](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.1-preview.20260921.2)；桌面当前预览：[`v0.1.1-preview.20260921.1`](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.1-preview.20260921.1)。
安装包、`SHA256SUMS` 和构建检查摘要均在发布页。校验和用于检查文件完整性，不等同于发布者数字签名。

| 平台 | 安装包与范围 | 验证情况与限制 |
|---|---|---|
| Android 8.0+ | [下载 APK](https://github.com/managecode-commits/zhimo/releases/download/v0.1.1-preview.20260921.2/zhimo-v0.1.1-preview.20260921.2-android-debug.apk)，约 323 MiB；arm64-v8a / armeabi-v7a / x86_64；内置 Rime、手写与流式语音模型 | 调试签名；27 项 JVM 测试、Lint、3 项模拟器模型与按键 UI 测试通过；真实手机中文口语、延迟与发热仍需验收 |
| Windows 64 位系统 | [下载 ZIP](https://github.com/managecode-commits/zhimo/releases/download/v0.1.1-preview.20260921.1/zhimo-v0.1.1-preview.20260921.1-windows.zip)，约 96 MiB；包含 x64 / x86 TSF 组件、模式状态栏、手写与语音面板 | 未代码签名；MinGW 双架构与依赖检查、Windows CI 的 MSVC 编译和 TSF 探针通过；发行 ZIP 在 EmEditor 等宿主仍需实机测试 |
| Ubuntu 24.04 amd64 | [下载 DEB 压缩包](https://github.com/managecode-commits/zhimo/releases/download/v0.1.1-preview.20260921.1/zhimo-v0.1.1-preview.20260921.1-ubuntu2404-amd64.tar.gz)，约 72 MiB；提供 IBus / Fcitx5 接入 | 目标容器构建、安装／卸载和 Rime 回归通过；真实桌面、Wayland 与麦克风仍需验收 |
| Debian 12 amd64 | [下载 DEB 压缩包](https://github.com/managecode-commits/zhimo/releases/download/v0.1.1-preview.20260921.1/zhimo-v0.1.1-preview.20260921.1-debian12-amd64.tar.gz)，约 69 MiB；提供 IBus / Fcitx5 接入 | 目标容器构建、安装／卸载和 Rime 回归通过；真实桌面、Wayland 与麦克风仍需验收 |
| macOS 12.0+ | [下载 Universal ZIP](https://github.com/managecode-commits/zhimo/releases/download/v0.1.1-preview.20260921.1/zhimo-v0.1.1-preview.20260921.1-macos-universal.zip)；Apple Silicon / Intel，内置手写与 Whisper | GitHub Mac runner 编译、Swift/C ABI 选词探针和 ad-hoc 签名校验通过；无 Developer ID 签名、未公证，IMK 宿主与麦克风需实机验收 |
| iOS / iPadOS | 容器与键盘扩展原型源码 | 尚无完整可安装产品 |

安装提示：

- **Android**：安装 APK 后打开 Zhimo，按引导启用并选择输入法。若旧版本签名不同，不能直接覆盖；先保留个人词库和配置，不要贸然卸载。
- **Windows**：完整解压 ZIP，以实际使用输入法的同一账户启动管理员 64 位 PowerShell，运行 `.\install.cmd`；升级使用 `.\install.cmd -Upgrade`。若提示下载脚本被阻止，核验来源后按提示确认 `UNBLOCK`；不要修改全局执行策略或绕过组织安全策略。保存文档后注销并重新登录，避免旧 DLL 仍被应用占用。
- **Linux**：先解压 `.tar.gz`，其中有 3 个 DEB：`zhimo-core` 是必需核心，`zhimo-ibus` 和 `fcitx5-zhimo` 是两种框架适配包。使用 APT 安装核心和所选框架包，以便解析依赖；不需要安装全部三个。不要混用不同发行版的包，也不要同时启用两套框架；详细步骤见发布说明和安装手册。

- **macOS**：解压后查看随包 README，运行 `install.command` 安装到当前用户；保存文档并注销登录，再在系统设置中添加知墨输入源。此包仅 ad-hoc 签名，不绕过组织安全策略；若系统阻止运行，请勿全局关闭 Gatekeeper。

本次没有 iOS 或鸿蒙安装包。Linux 新字形融合实验尚未接入，本版仍使用现有单字手写链路，不能视为已完成 Android 手写能力移植。

## 离线与个人数据

日常本地输入无需登录账号，已交付的离线识别路径不依赖云端 API。
语音模型随安装包提供，因此包体积较大；模型准备与许可说明见[内置离线语音交付](docs/内置离线语音交付.md)。

供开发者使用的 `data-cli` 可以查看个人学习记录、标记删除，以及加密导入和导出。
**“标记删除”是记录某条学习数据已被删除**，供数据合并和同步时识别删除状态，
避免旧副本把它重新带回来；它不等同于彻底擦除所有副本。

仓库另有[同步服务源码](services/sync-server/README.md)，不代表安装 App 后就会自动开通云同步。
请妥善保管导出文件和加密密钥，不将个人词库、录音或密钥提交到公开仓库。

## 给开发者：可复用的输入法底座

Zhimo Core 使用 Rust，通过 C ABI 连接各平台适配层；界面与语言、手写、语音引擎分离，
便于复用词库和学习能力，并扩展其他语种。仓库包含英文、拼音、越南语引擎及相关平台工程；
核心可复用不意味着所有平台和语种都已完成产品验收。

基础验证（先安装 Rust；完整环境要求见构建手册）：

```bash
git clone https://github.com/managecode-commits/zhimo.git
cd zhimo
cargo test --workspace --locked
cargo run -p ime-cli -- hello
cargo run -p ime-cli -- pinyin nihao
```

Android Rime 构建入口：`./tools/verify-android-rime-beta.sh`。
请先按手册配置已接受许可的 Android SDK、NDK 与 JDK；此脚本还会在已连接的授权设备上安装和测试。

更多文档：

- [全平台构建、安装与验收](docs/构建与验收.md)
- [产品与架构总体方案](docs/项目总体方案.md)
- [实现状态](docs/实现状态.md)与[发布、真机验收条件](docs/发布阻断与真机验收.md)
- [桌面键盘与拼音操作](docs/桌面键盘字符与拼音输入.md)
- [品牌与升级兼容说明](docs/品牌与兼容标识.md)、[图标与平台接入](assets/branding/README.md)

## 参与改进

欢迎反馈拼音候选、应用兼容、手写误识别和语音转录问题。
请提供系统、安装包版本、应用名称、复现步骤及预期文字；手写问题最好附目标字和笔迹录屏。
分享前请遮挡私密文档、账号和聊天内容，不上传密码、个人词库或未经同意的录音。

欢迎参与平台适配、界面与无障碍、词条校对、测试和其他语种开发。
提交改动前请阅读[贡献指南](CONTRIBUTING.md)；安全问题请按[安全说明](SECURITY.md)反馈。

## 作者与开源许可

作者：**立方田** · 邮箱：[managecode@gmail.com](mailto:managecode@gmail.com)

Copyright © 2026 立方田。项目自有代码采用 [Apache-2.0](LICENSE)。
第三方组件、词库和模型保留各自版权与许可证，不被项目许可证替代，详见[第三方声明](THIRD_PARTY_NOTICES.md)。
安装包通过 Releases 分发；签名私钥和个人学习数据不入库。
