# Android 快速输入优化与验收

Copyright © 2026 立方田 <managecode@gmail.com>

## 范围（2026-09-21）

针对手机虚拟键盘快速打字的滞后感，调整 Android 输入与绘制路径；不修改词库、排序、学习规则，也不改变语音默认引擎。
代码检查发现按键点击同步执行 native 查询及 JSON 解析，候选栏逐次重建，9 键侧栏重复构建，触摸反馈路径同步查询系统声音和振动策略。
这些是已确认的主线程开销来源，但尚无用户实体手机上的各阶段耗时分布，不能声称已查明某机型的唯一瓶颈。

## 实现

- `OrderedInputQueue` 协调输入顺序。字母、数字编码、空格、回车、删除、选词的 native 操作及动作解析在单一后台线程执行；编辑器提交和控件更新仍在主线程进行。
- 普通按键与模式切换排队，不采用“一键一个并发任务”，不对输入事件做丢弃式防抖。模式切换、初始化等低频 native 调用仍在主线程，但只在后台输入操作结束后执行，禁止同时访问句柄。
- 新输入会话及结束输入时使旧任务失效；旧任务可以完成原生计算，但不能向新编辑器提交结果。销毁句柄排在当前任务之后，不提前释放工作中的对象。
- 候选条复用最多五个 TextView，更新文字、注音与候选 ID；候选数量变化时才增删控件。9 键侧栏内容未变化时不重建。展开候选及拼音选项带版本/内容检查，旧候选不能操作新输入状态。
- 后台运算期间，候选条不接受旧词点击；更新完成后可选词。不会把旧行的第一个词猜作新输入的第一个词。
- 复用按键气泡窗口和文字控件，避免每次触摸新建整套对象。空格/分词标签未变化时不重复设置文字。
- 音效及可调振动的系统策略查询与播放移至独立串行线程，保留静音、勿扰和录音期间暂停规则；系统 View 触觉调用仍回到主线程。过时超过 60 毫秒的反馈不补播，这只跳过声音/振动，不跳过输入。

## 验证入口

JVM：`OrderedInputQueueTest` 覆盖 100 次快速排队、模式屏障、编辑器换代、异常后继续及回调重入顺序。

Android：`OrderedNativeInputTest` 使用独立测试词频目录，覆盖 26 键、9 键和删除穿插三种批量输入；每种提交 20 次“你好”，检查无缺失、无重复、无乱序，并检查主线程仍能处理消息。

```bash
adb shell am instrument -w -e class dev.zhimo.ime.OrderedNativeInputTest \
  dev.zhimo.ime.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.zhimo.ime.KeyboardInteractionTest \
  -e runKeyboardUi true dev.zhimo.ime.test/androidx.test.runner.AndroidJUnitRunner
```

第二组只应在专用模拟器运行：测试会临时切换输入法并恢复原设置。包含拼音分词、候选、标点、9/26 键及其他键盘模式回归。

性能追踪区段为 `Zhimo.engine.edit` 和 `Zhimo.candidates.apply`，不含输入正文。`ZhimoInputTest` 日志仅包含测试模式、操作数和总耗时。
批量测试不是触摸到屏幕显示的端到端 P95/P99，也不是实体手机流畅度证明。

## 实体手机待验收

请分别测试 9 键和 26 键：连续拼音、快速删除、输入后立即按空格、快速切换中英文、输入框切换；保留音效/振动开启进行测试。
还需测量实际设备触摸反馈、候选显示延迟、冷启动、低内存及语音模型已加载时的表现。启动时资源准备、低频模式操作及引擎本身查询开销仍可能成为下一步优化对象。

## 本轮结果与安装包

- APK：`target/packages/zhimo-keyboard-responsive-20260921-debug.apk`（约 389 MiB，调试签名）。保留前一版本的流式语音实验能力，Whisper 仍为默认。
- SHA-256：`575d4f371b7343e15511d5e88dc44ed510a0805164099e8b018980ef13a7e1cb`。
- 完整构建及 Lint 通过；18 项 JVM 测试通过；原生批量输入和反馈共 6 项 Android 测试通过；完整键盘交互 1 项通过。
- 模拟器批量输入：9 键 120 次操作 3892 ms、26 键 120 次操作 1685 ms、26 键含删除 160 次操作 1734 ms。全部精确提交 20 次“你好”，无丢字、重复或乱序。时间包含排队和主线程回调，不是单次按键显示延迟，也没有与旧 APK 做同条件速度对照。
- 主包签名、三 ABI 原生依赖、模型及许可文件校验通过。
- 第一次完整交互测试被模拟器 System UI ANR 弹窗遮挡；截图确认并关闭后重跑。随后原有 80 ms 固定等待不能保证异步拼音已返回，将该检查改为有超时的预期拼音条件等待，未改变预期拼音或上屏文字，最终通过。
- 日志：`target/streaming-speech/keyboard-final-build.log`、`keyboard-native-tests.log`、`keyboard-ui-await.log`、`keyboard-apk-check.log`；测试包更新后的 Lint 见 `keyboard-test-update.log`。
- 未提交 Git 或发布 GitHub Release；实体手机主观手感及 P95/P99 仍待验证。
