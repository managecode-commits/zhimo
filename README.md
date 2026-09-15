<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Zhimo · 知墨输入法

![Zhimo](assets/branding/zhimo-128.png)

[图标源文件与各平台接入说明](assets/branding/README.md)

跨平台、多语种、隐私优先的智能输入与语音转录平台。

**让文字输入留在本机：拼音、手写、离线语音，一个可扩展的输入法底座。**

Zhimo 面向希望使用本地输入工具的用户，也面向开发新语种输入法的开发者。
日常输入无需登录账号；已交付的离线识别路径不依赖云端 API。语音模型较大，
安装包体积和识别速度存在取舍；手写仍在持续改进，不承诺所有写法都能准确识别。

[版本发布页](https://github.com/managecode-commits/zhimo/releases) ·
[构建与安装](docs/构建与验收.md) ·
[反馈问题](https://github.com/managecode-commits/zhimo/issues) ·
[参与贡献](CONTRIBUTING.md)

## 能做什么

- **中文拼音**：全拼、简拼、候选选择，支持常用词条与本地词频学习；不同平台的引擎和接入进度不同。
- **离线单字手写**：桌面画板、候选点选、撤笔重写；Android 还在探索双模型融合和连写识别。
- **离线语音转文字**：录音结束后本地转录、预览并确认上屏，不是实时流式听写。
- **可扩展底座**：Rust 核心与 C ABI 分离平台界面，便于复用词库、学习、识别及其他语种引擎。

## 平台状态：先看这里

| 平台 | 当前交付状态 | 重要边界 |
|---|---|---|
| Windows 64 位系统 | x64／x86 TSF 开发测试包，拼音、手写、语音、模式状态栏 | 新版多应用兼容与状态栏修复仍需实机验证；未代码签名 |
| Ubuntu 24.04 / Debian 12 amd64 | IBus／Fcitx5 DEB；容器安装、回归测试通过 | GNOME／KDE、Wayland 和真实麦克风仍需验收 |
| Android | 可构建 APK，已有设备测试与用户问题反馈 | 连写及字形识别准确率仍在迭代；本轮桌面预览不另发 APK |
| macOS | 已有 IMK、手写／语音及 Universal 构建脚本源码 | 未在 Mac 编译和验收，没有已验证的安装包 |
| iOS/iPadOS | 容器与键盘扩展原型源码 | 尚不是可直接安装的完整产品 |

预览包只面向愿意反馈问题的测试用户。发布内容以 Releases 页的具体版本说明为准；
不会把“核心可移植”宣传为“所有平台都已可用”。

## 如何帮助改进

欢迎测试拼音候选、应用兼容、手写漏字和离线转录。反馈时请注明系统、输入法版本、
应用名称、复现步骤与预期文字。手写问题最好附目标字及笔迹录屏；提交前请遮挡私密文档、
账号及聊天内容，不上传密码、个人词库或未经同意的录音。

开发者可从平台适配、测试用例、无障碍、词条校对和其他语种引擎入手。欢迎小范围、可复现的改进。

---

开源仓库：https://github.com/managecode-commits/zhimo

作者：立方田 · 电子邮箱：[managecode@gmail.com](mailto:managecode@gmail.com)

Copyright © 2026 立方田。项目采用 [Apache-2.0 许可证](LICENSE)；第三方组件、词库及模型保留各自版权与许可证，见 [第三方声明](THIRD_PARTY_NOTICES.md)。

当前为开发预览项目，并非五个平台均已完成正式发布验收。中文连写仍在质量改进中；不要将实验性识别能力视为准确率保证。

产品名为 **知墨输入法 / Zhimo IME**，通用底座为 **Zhimo Core**。工程包名、文件名和品牌相关接口标识统一采用 Zhimo；旧应用包名、JNI 符号及带品牌的源码引用需要迁移，通用 `ime_*` C ABI 保留。升级注意事项见 [品牌与兼容标识](docs/品牌与兼容标识.md)。

当前仓库包含通用输入运行时、英文/拼音/越南语引擎、生产 librime Adapter、离线 whisper.cpp Provider、SQLite 学习、加密同步、签名语言包、C ABI、Linux IBus/Fcitx 5 以及 Android/Apple/Windows 平台工程边界。完整产品与架构范围参见 [项目总体方案](docs/项目总体方案.md)。

当前实际完成度和外部验证条件参见 [实现状态](docs/实现状态.md)。
Android 新增随包内置多语种语音模型与本地麦克风转录，使用方式、构建和验收边界见 [内置离线语音交付](docs/内置离线语音交付.md)。这是停止录音后转录，不是实时流式听写。
所有 Android、Windows、Linux、macOS、iOS、命令行 App 和同步服务的环境准备、编译、
安装及验收命令统一收录在 [全平台构建、安装与验收手册](docs/构建与验收.md)。
需要项目所有者处理的许可证、签名、目标设备和发布服务条件参见
[发布阻断与真机验收](docs/发布阻断与真机验收.md)。

## 快速验证

```bash
git clone https://github.com/managecode-commits/zhimo.git
cd zhimo
```

```bash
cargo test --workspace
cargo run -p ime-cli -- hello
cargo run -p ime-cli -- pinyin nihao
./tools/verify.sh
```

## 开源许可与贡献

项目自有代码沿用 [Apache-2.0](LICENSE)。第三方模型、词库与衍生算法分别遵循原许可证，不被项目许可证替代；详见 [第三方说明](THIRD_PARTY_NOTICES.md)。手写模型及对应源数据随仓库提供；语音权重通过固定版本与哈希的准备脚本获取，再随 APK 内置。安装包、签名密钥和本地学习数据不入库。

提交问题或改进前请阅读 [贡献指南](CONTRIBUTING.md)，安全问题参见 [安全说明](SECURITY.md)。

Android 完整 Rime Beta（自动获取并编译三个 ABI 的 `librime.so`、打包 APK，并在已连接
设备上运行测试）：

```bash
./tools/verify-android-rime-beta.sh
```

真实离线转录：

```bash
cargo run -p transcribe-cli -- <whisper-cli> <model.bin> <audio.wav> en
```

个人学习数据可通过 `data-cli` 查看、生成删除墓碑以及加密导入/导出。同步服务见 `services/sync-server/README.md`。

## 工程原则

- 平台适配层不包含语言逻辑。
- 语言与语音引擎不直接操作 UI、网络和麦克风。
- 基础输入离线可用，失败时可以安全退回原始输入。
- 公共核心不出现拼音、汉字等单一语种专属模型。
- 用户学习和语音数据遵循最小化、可撤销、可导出原则。
