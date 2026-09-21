# Android 流式语音与按住说话

Copyright © 2026 立方田 <managecode@gmail.com>

2026-09-21：从 Android 版本码 5 起，手机端内置语音仅使用中英 Zipformer 流式模型。
桌面端仍使用既有 Whisper 链路，此次不改动桌面安装包。已发布的版本码 4 APK 仍是旧行为。

## 交互

- 短按空格：保持原有空格／拼音选词行为。
- 按住空格达到长按阈值：启动离线语音；等待“松开结束”再说话。首次准备可能较慢。
- 松开：停止采音，处理剩余音频，最终文字直接上屏一次，不再确认。
- 滑出按键、触摸取消、多指打断、键盘销毁：取消本次录音，不上屏。
- 准备期间松开：取消任务，不能在松开后才启动麦克风。
- 切换输入框、光标移动、密码输入域：保留会话隔离，不向其他编辑器提交。
- 临时结果仅在键盘内预览；不把可能修订的中间结果逐次写入宿主。
- 无障碍长按动作没有持续触摸，保留长按启动、点击停止的备用方式。

## 模型与设置

移除 Android Whisper JNI、Whisper 模型和独立 Silero VAD 资产，不再提供两套离线模型切换。
设置移除无效的 Whisper 热词、独立 VAD、确认上屏、实验流式开关和系统识别回退开关；
旧设置值不影响新的流式直出行为。保留简体输出选项。

升级后第一次准备模型时，仅清理应用私有目录中两个已知旧模型文件：
Base Q5_1 与 Silero 的固定哈希文件。保留用户词库、配置、其他文件；不递归删除语音目录。
共享仓库中的桌面 Whisper 模型不删除。

流式 JNI 为固定 sherpa-onnx v1.12.11 的 ASR-only 构建，与手写复用 ONNX Runtime 1.22。
模型、运行库与许可均随包交付，不在录音时联网下载。最长录音 60 秒，
音频队列最多 600 个 100ms 小块（约 3.7 MiB），允许低速手机积压解码，不静默丢音。
停止后仍可能需要等待剩余解码；不宣称所有手机都达到实时速度。

流式模型本身体积约 199 MiB，因此相对“Whisper＋流式双模型实验包”会减小，
但不应与仅含 Whisper 的约 180 MiB 旧 APK 比较后声称更小。准确大小以实际产物为准。

## 构建与验证

准备 Android SDK / NDK / JDK 和 Rime 后：

```bash
bash tools/verify-android-beta.sh
```

脚本准备固定流式模型、ASR-only JNI 和资产，构建 APK，并检查其中模型哈希、许可、三 ABI
及没有 Whisper/VAD 旧资产。原来的 `verify-android-streaming-beta.sh` 作为兼容入口调用相同流程。
不再需要 `-Pzhimo.streamingSpeech=true`；显式传 false 会拒绝构建，防止产出不可用 APK。
正式签名构建继续使用 `tools/verify-android-release.sh`，凭据要求不变。

回归应覆盖手势短按/长按/取消、延迟队列开始、跨触摸旧任务、模型取消、静音和公开语音样例；
麦克风 UI 测试用真实 DOWN/UP 事件验证，而不是用第二次点击替代松手。
手机端真实中文口语、首次准备耗时、录音权限撤回和低端 ARM 设备仍需单独验收。

## 本轮产物与验证

- APK：`target/packages/zhimo-v0.1.1-preview.20260921.2-android-debug.apk`，
  versionCode 5，339,142,698 字节（约 323 MiB），三 ABI、调试签名；发布说明见 `release/preview-20260921.2.md`。
- SHA-256：`76eaad273660dbc953da6937185aaf940c2736152105af2c4c7fda3bdcdbeeeb`。
- Gradle 构建与 Lint 通过；27 项 JVM 测试通过，包括 6 项按住说话手势状态测试、1 项旧模型定向清理测试。
- API 35 x86_64 模拟器：2 项流式测试通过，覆盖模型校验、取消安装、静音、重复识别流及公开 JFK 样例的临时和最终结果。
- 按键 UI 集成测试 1 项通过，启用 `runOfflineMic=true`，使用实际触摸 DOWN/UP 事件验证按住录音、松手结束、静音不上屏额外空格及移动光标取消。
- 实际 APK 签名、模型/许可哈希、ASR-only JNI、共享 ORT 和三 ABI 检查通过；明确拒绝 `assets/speech/` 与 `libzhimo_speech.so`。
- 日志：`target/streaming-hold-20260921-build-retry.log`、`apk-check.log`、
  `model-tests.log`、`ui-tests.log`（后三个文件同样使用 `streaming-hold-20260921-` 前缀）。
- 同步构建来源声明后重新打包并安装最终 APK，合并运行模型和按键 UI 测试，3 项全部通过。
  最终日志：`target/streaming-hold-20260921-final-build.log`、
  `target/streaming-hold-20260921-final-apk-check.log`、
  `target/streaming-hold-20260921-final-device-tests.log`。

模拟器验证不能替代真实手机的中文口语准确率、耗时和发热测试。首次构建发现 AGP 不接受旧式 SourceSet 的 Provider 参数，已改为明确目录并声明资源生成依赖；重建通过。
