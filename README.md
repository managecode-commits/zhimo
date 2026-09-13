# Zhimo · 知墨输入法

跨平台、多语种、隐私优先的智能输入与语音转录平台。

开源仓库：https://github.com/managecode-commits/zhimo

当前为开发预览项目，并非五个平台均已完成正式发布验收。中文连写仍在质量改进中；不要将实验性识别能力视为准确率保证。

产品名为 **知墨输入法 / Zhimo IME**，通用底座称为 **Zhimo Core**。原工程名称 `shurufa` 保留在应用标识、接口和数据路径中，以兼容已有安装与调用方；命名规则见 [品牌与兼容标识](docs/品牌与兼容标识.md)。

当前仓库包含通用输入运行时、英文/拼音/越南语引擎、生产 librime Adapter、离线 whisper.cpp Provider、SQLite 学习、加密同步、签名语言包、C ABI、Linux IBus/Fcitx 5 以及 Android/Apple/Windows 平台工程边界。完整产品与架构范围参见 [项目总体方案](docs/项目总体方案.md)。

当前实际完成度和外部验证条件参见 [实现状态](docs/实现状态.md)。
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

项目自有代码沿用 [Apache-2.0](LICENSE)。第三方模型、词库与衍生算法分别遵循原许可证，不被项目许可证替代；详见 [第三方说明](THIRD_PARTY_NOTICES.md)。模型及对应源数据随仓库提供，安装包、签名密钥和本地学习数据不入库。

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
