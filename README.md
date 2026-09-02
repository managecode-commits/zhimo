# Shurufa

跨平台、多语种、隐私优先的智能输入与语音转录平台。

当前仓库包含通用输入运行时、英文/拼音/越南语引擎、生产 librime Adapter、离线 whisper.cpp Provider、SQLite 学习、加密同步、签名语言包、C ABI、Linux IBus/Fcitx 5 以及 Android/Apple/Windows 平台工程边界。完整产品与架构范围参见 [项目总体方案](docs/项目总体方案.md)。

当前实际完成度和外部验证条件参见 [实现状态](docs/实现状态.md)。
需要项目所有者处理的许可证、签名、目标设备和发布服务条件参见
[发布阻断与真机验收](docs/发布阻断与真机验收.md)。

## 快速验证

```bash
cargo test --workspace
cargo run -p ime-cli -- hello
cargo run -p ime-cli -- pinyin nihao
./tools/verify.sh
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
