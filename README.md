<!-- Copyright © 2026 立方田 <managecode@gmail.com> -->
# Zhimo · 知墨输入法

![知墨 Zhimo](assets/branding/zhimo-128.png)

**拼音、手写、离线语音，让输入更贴近自己的表达。**

Zhimo 是一个开源、离线优先的输入法项目。你可以用拼音打字、手写选字，
也可以录音后在本机转成文字；开发者可以基于 Rust 核心扩展其他语种和平台。

**当前为开发预览版，不是稳定正式版。** Android、Windows、Ubuntu 和 Debian 已提供测试安装包；
macOS 与 iOS 仍在开发中。各平台功能与完成度不完全相同。

[下载安装包](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.0-preview.20260915.1) ·
[构建与安装手册](docs/构建与验收.md) ·
[图文介绍](docs/promotion/知墨输入法图文宣传稿.md) ·
[反馈问题](https://github.com/managecode-commits/zhimo/issues) ·
[参与贡献](CONTRIBUTING.md)

## 主要功能

- **拼音输入**：全拼、简拼、汉字候选与常用词条；Android 提供九键和 26 键，支持手动分词和九键拼音候选切换。
- **本地学习**：根据选词记录调整候选排序，帮助常用词和组合更容易被找到。具体行为取决于平台、引擎和学习设置。
- **离线手写**：书写、查看候选、点选上屏，支持撤笔和清空。桌面以单字为主，Android 的字形与连写识别仍在改进。
- **离线语音转文字**：录音结束后在本机识别，预览并确认上屏；不是实时流式听写。识别速度与效果受设备、噪声及发音影响。
- **字符与常用表达**：英文、数字、标点输入；Android 提供符号与 Emoji 入口。

桌面输入示例：`nihao` / `nh` 可查找“你好”；输入 `jixu` 后，空格确认汉字候选，
回车直接上屏原始拼音 `jixu`。没有拼音组合时，回车交给当前应用正常处理。

## 下载与平台状态

当前预览版本：[`v0.1.0-preview.20260915.1`](https://github.com/managecode-commits/zhimo/releases/tag/v0.1.0-preview.20260915.1)。
安装包及 SHA-256 校验文件均在发布页。

| 平台 | 安装包与范围 | 验证情况与限制 |
|---|---|---|
| Android 8.0+ | APK，约 181 MiB；arm64-v8a / armeabi-v7a / x86_64；内置 Rime、手写与语音模型 | 调试签名；构建和包校验通过，Lint 0 错误、13 警告；本轮未进行真机验收 |
| Windows 64 位系统 | ZIP，包含 x64 / x86 TSF 组件、模式状态栏、手写与语音面板 | 未代码签名；交叉编译与依赖检查通过，多应用兼容仍需实机测试 |
| Ubuntu 24.04 / Debian 12 amd64 | DEB，提供 IBus / Fcitx5 接入 | 目标容器构建、安装及回归通过；真实桌面、Wayland 与麦克风仍需验收 |
| macOS | 源码与构建脚本 | 未在 Mac 编译验收，不提供已验证安装包 |
| iOS / iPadOS | 容器与键盘扩展原型源码 | 尚无完整可安装产品 |

安装提示：

- **Android**：安装 APK 后打开 Zhimo，按引导启用并选择输入法。若旧版本签名不同，不能直接覆盖；先保留个人词库和配置，不要贸然卸载。
- **Windows**：完整解压 ZIP，在管理员 64 位 PowerShell 中运行 `.\install.cmd`；升级使用 `.\install.cmd -Upgrade`。保存文档后注销并重新登录，避免旧 DLL 仍被应用占用。
- **Linux**：选择对应发行版的 `zhimo-core`，再安装 `zhimo-ibus` 或 `fcitx5-zhimo`。不要混用不同发行版的包，也不要同时启用两套框架；详细步骤见发布说明和安装手册。

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
